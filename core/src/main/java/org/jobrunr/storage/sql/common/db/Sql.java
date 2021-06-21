package org.jobrunr.storage.sql.common.db;

import org.jobrunr.storage.StorageException;
import org.jobrunr.storage.sql.common.db.dialect.Dialect;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Arrays.stream;
import static org.jobrunr.JobRunrException.shouldNotHappenException;
import static org.jobrunr.storage.sql.common.db.ConcurrentSqlModificationException.concurrentDatabaseModificationException;
import static org.jobrunr.utils.StringUtils.isNullOrEmpty;
import static org.jobrunr.utils.reflection.ReflectionUtils.getValueFromFieldOrProperty;
import static org.jobrunr.utils.reflection.ReflectionUtils.objectContainsFieldOrProperty;

public class Sql<T> {
    private static final String INSERT = "insert ";
    private static final String UPDATE = "update ";
    private static final String DELETE = "delete ";

    private final List<String> paramNames;
    private final Map<String, Object> params;
    private final Map<String, Function> paramSuppliers;
    private DataSource dataSource;
    private Dialect dialect;
    private String tablePrefix = "";
    private String suffix = "";

    private volatile static Map<Integer, ParsedStatement> parsedStatementCache = new ConcurrentHashMap<>();
    private String tableName;

    protected Sql() {
        paramNames = new ArrayList<>();
        params = new HashMap<>();
        paramSuppliers = new HashMap<>();
    }

    public static <T> Sql<T> forType(Class<T> tClass) {
        return new Sql<>();
    }

    public static Sql<?> withoutType() {
        return new Sql<>();
    }

    public Sql<T> using(DataSource dataSource, Dialect dialect, String tablePrefix, String tableName) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
        this.tableName = tableName;
        return this;
    }

    public Sql<T> with(String name, Enum<?> value) {
        params.put(name, value.name());
        return this;
    }

    public Sql<T> with(String name, Object value) {
        params.put(name, value);
        return this;
    }

    public Sql<T> with(String name, Function<T, Object> function) {
        paramSuppliers.put(name, function);
        return this;
    }

    public Sql<T> withVersion(Function<T, Integer> function) {
        paramSuppliers.put("version", function);
        return this;
    }

    public Sql<T> withOrderLimitAndOffset(String order, int limit, long offset) {
        with("limit", limit);
        with("offset", offset);
        suffix = dialect.limitAndOffset(order);
        return this;
    }

    public Stream<SqlResultSet> select(String statement) {
        String parsedStatement = parse("select " + statement + suffix);
        SqlSpliterator sqlSpliterator = new SqlSpliterator(dataSource, parsedStatement, this::setParams);
        return StreamSupport.stream(sqlSpliterator, false);
    }

    public Stream<SqlResultSet> execute(String statement) {
        String parsedStatement = parse(statement + suffix);
        SqlSpliterator sqlSpliterator = new SqlSpliterator(dataSource, parsedStatement, this::setParams);
        return StreamSupport.stream(sqlSpliterator, false);
    }

    public long selectCount(String statement) {
        String parsedStatement = parse("select count(*) " + statement);
        try (final Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(parsedStatement)) {
            setParams(ps);
            try (ResultSet countResultSet = ps.executeQuery()) {
                countResultSet.next();
                return countResultSet.getLong(1);
            }
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    public boolean selectExists(String statement) {
        return selectCount(statement) > 0;
    }

    public void insert(T item, String statement) {
        try (final Connection conn = dataSource.getConnection(); final Transaction tran = new Transaction(conn)) {
            insertOrUpdate(conn, item, INSERT + statement);
            tran.commit();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    public void insert(Connection conn, String statement) throws SQLException {
        insertOrUpdate(conn, null, INSERT + statement);
    }

    public void insert(Connection conn, T item, String statement) throws SQLException {
        insertOrUpdate(conn, item, INSERT + statement);
    }

    public void update(String statement) {
        try (final Connection conn = dataSource.getConnection(); final Transaction tran = new Transaction(conn)) {
            insertOrUpdate(conn, null, UPDATE + statement);
            tran.commit();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    public void update(T item, String statement) {
        try (final Connection conn = dataSource.getConnection(); final Transaction tran = new Transaction(conn)) {
            insertOrUpdate(conn, item, UPDATE + statement);
            tran.commit();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    public void update(Connection conn, T item, String statement) throws SQLException {
        insertOrUpdate(conn, item, UPDATE + statement);
    }

    public void update(Connection conn, String statement) throws SQLException {
        insertOrUpdate(conn, null, UPDATE + statement);
    }

    public int delete(String statement) {
        try (final Connection conn = dataSource.getConnection(); final Transaction tran = new Transaction(conn)) {
            final int delete = delete(conn, statement);
            tran.commit();
            return delete;
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    public int delete(Connection conn, String statement) {
        String parsedStatement = parse(DELETE + statement);
        try (PreparedStatement ps = conn.prepareStatement(parsedStatement)) {
            setParams(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    private void insertOrUpdate(Connection conn, T item, String statement) throws SQLException {
        String parsedStatement = parse(statement);
        try (PreparedStatement ps = conn.prepareStatement(parsedStatement)) {
            setParams(ps, item);
            final int updated = ps.executeUpdate();
            if (updated != 1) {
                throw concurrentDatabaseModificationException(item, updated);
            }
        } catch (SQLException e) {
            String lowerCaseMessage = e.getMessage().toLowerCase();
            if (e.getErrorCode() == -803 || lowerCaseMessage.contains("duplicate") || lowerCaseMessage.contains("primary key") || lowerCaseMessage.contains("unique constraint")) {
                throw concurrentDatabaseModificationException(item, 0);
            }
            throw e;
        }
    }

    public void insertAll(List<T> batchCollection, String statement) {
        int[] result = insertOrUpdateAll(batchCollection, INSERT + statement);
        if (result.length != batchCollection.size()) {
            throw shouldNotHappenException("Could not insert or update all objects - different result size: originalCollectionSize=" + batchCollection.size() + "; " + Arrays.toString(result));
        } else if (stream(result).anyMatch(i -> i < Statement.SUCCESS_NO_INFO || i == 0)) {
            throw concurrentDatabaseModificationException(batchCollection, result);
        }
    }

    public void updateAll(List<T> batchCollection, String statement) {
        int[] result = insertOrUpdateAll(batchCollection, UPDATE + statement);
        if (result.length != batchCollection.size()) {
            throw shouldNotHappenException("Could not insert or update all objects - different result size: originalCollectionSize=" + batchCollection.size() + "; " + Arrays.toString(result));
        } else if (stream(result).anyMatch(i -> i < 1)) {
            throw concurrentDatabaseModificationException(batchCollection, result);
        }
    }

    private int[] insertOrUpdateAll(List<T> batchCollection, String statement) {
        String parsedStatement = parse(statement);
        try (final Connection conn = dataSource.getConnection(); final Transaction tran = new Transaction(conn, false)) {
            try (PreparedStatement ps = conn.prepareStatement(parsedStatement)) {
                for (T object : batchCollection) {
                    setParams(ps, object);
                    ps.addBatch();
                }
                final int[] result = ps.executeBatch();
                tran.commit();
                return result;
            }
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    private void setParams(PreparedStatement ps) {
        setParams(ps, null);
    }

    private void setParams(PreparedStatement ps, T object) {
        for (int i = 0; i < paramNames.size(); i++) {
            String paramName = paramNames.get(i);
            if (params.containsKey(paramName)) {
                setParam(ps, i + 1, params.get(paramName));
            } else if (paramSuppliers.containsKey(paramName)) {
                setParam(ps, i + 1, paramSuppliers.get(paramName).apply(object));
            } else if (objectContainsFieldOrProperty(object, paramName)) {
                setParam(ps, i + 1, getValueFromFieldOrProperty(object, paramName));
            } else if ("previousVersion".equals(paramName)) {
                setParam(ps, i + 1, ((int) paramSuppliers.get("version").apply(object)) - 1);
            } else {
                throw new IllegalArgumentException(String.format("Parameter %s is not known.", paramName));
            }
        }
    }

    private void setParam(PreparedStatement ps, int i, Object o) {
        try {
            if (o instanceof Integer) {
                ps.setInt(i, (Integer) o);
            } else if (o instanceof Long) {
                ps.setLong(i, (Long) o);
            } else if (o instanceof Double) {
                ps.setDouble(i, (Double) o);
            } else if (o instanceof Boolean) {
                ps.setInt(i, (boolean) o ? 1 : 0);
            } else if (o instanceof Instant) {
                ps.setTimestamp(i, Timestamp.from((Instant) o));
            } else if (o instanceof Duration) {
                ps.setString(i, o.toString());
            } else if (o instanceof Enum) {
                ps.setString(i, ((Enum<?>) o).name());
            } else if (o instanceof UUID || o instanceof String) {
                ps.setString(i, o.toString());
            } else if (o == null) {
                //TODO hack, use TypeResolver to keep track of type
                ps.setTimestamp(i, null);
            } else {
                throw new IllegalStateException(String.format("Found a value which could not be set in the preparedstatement: %s: %s", o.getClass(), o));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    final String parse(String query) {
        final ParsedStatement parsedStatement = parsedStatementCache.computeIfAbsent(query.hashCode(), hash -> createParsedStatement(query));
        paramNames.clear();
        paramNames.addAll(parsedStatement.paramNames);
        return parsedStatement.sqlStatement;
    }

    final ParsedStatement createParsedStatement(String query) {
        final String parsedStatement = parseStatement(query);
        return new ParsedStatement(parsedStatement, new ArrayList<>(paramNames));
    }

    final String parseStatement(String query) {
        paramNames.clear();
        // I was originally using regular expressions, but they didn't work well for ignoring
        // parameter-like strings inside quotes.
        int length = query.length();
        StringBuilder parsedQuery = new StringBuilder(length);
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < length; i++) {
            char c = query.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                }
            } else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                }
            } else {
                if (c == '\'') {
                    inSingleQuote = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                } else if (c == ':' && i + 1 < length &&
                        Character.isJavaIdentifierStart(query.charAt(i + 1)) && !parsedQuery.toString().endsWith(":")) {
                    int j = i + 2;
                    while (j < length && Character.isJavaIdentifierPart(query.charAt(j))) {
                        j++;
                    }
                    String name = query.substring(i + 1, j);
                    c = '?'; // replace the parameter with a question mark
                    i += name.length(); // skip past the end if the parameter

                    paramNames.add(name);
                }
            }
            parsedQuery.append(c);
        }
        return parsedQuery.toString()
                .replace(tableName, tablePrefix + tableName);
    }

    private static class ParsedStatement {
        private final String sqlStatement;
        private final List<String> paramNames;

        public ParsedStatement(String sqlStatement, List<String> paramNames) {
            this.sqlStatement = sqlStatement;
            this.paramNames = paramNames;
        }
    }
}
