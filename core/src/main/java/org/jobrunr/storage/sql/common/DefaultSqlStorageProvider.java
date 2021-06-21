package org.jobrunr.storage.sql.common;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.*;
import org.jobrunr.storage.sql.SqlStorageProvider;
import org.jobrunr.storage.sql.common.db.Sql;
import org.jobrunr.storage.sql.common.db.SqlResultSet;
import org.jobrunr.storage.sql.common.db.dialect.Dialect;
import org.jobrunr.utils.resilience.RateLimiter;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.jobrunr.storage.sql.common.DefaultSqlStorageProvider.DatabaseOptions.CREATE;
import static org.jobrunr.utils.resilience.RateLimiter.Builder.rateLimit;
import static org.jobrunr.utils.resilience.RateLimiter.SECOND;

public class DefaultSqlStorageProvider extends AbstractStorageProvider implements SqlStorageProvider {

    public enum DatabaseOptions {
        CREATE,
        SKIP_CREATE;
    }

    private final DataSource dataSource;
    private final Dialect dialect;
    private final String tablePrefix;
    private final DatabaseOptions databaseOptions;
    private JobMapper jobMapper;

    public DefaultSqlStorageProvider(DataSource dataSource, Dialect dialect, DatabaseOptions databaseOptions) {
        this(dataSource, dialect, databaseOptions, rateLimit().at1Request().per(SECOND));
    }

    public DefaultSqlStorageProvider(DataSource dataSource, Dialect dialect, String tablePrefix, DatabaseOptions databaseOptions) {
        this(dataSource, dialect, tablePrefix, databaseOptions, rateLimit().at1Request().per(SECOND));
    }

    public DefaultSqlStorageProvider(DataSource dataSource, Dialect dialect, DatabaseOptions databaseOptions, RateLimiter changeListenerNotificationRateLimit) {
        this(dataSource, dialect, null, databaseOptions, changeListenerNotificationRateLimit);
    }

    DefaultSqlStorageProvider(DataSource dataSource, Dialect dialect, String tablePrefix, DatabaseOptions databaseOptions, RateLimiter changeListenerNotificationRateLimit) {
        super(changeListenerNotificationRateLimit);
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.tablePrefix = tablePrefix;
        this.databaseOptions = databaseOptions;
        createDBIfNecessary();
    }

    protected void createDBIfNecessary() {
        if (databaseOptions == CREATE) {
            getDatabaseCreator()
                    .runMigrations();
        } else {
            getDatabaseCreator()
                    .validateTables();
        }
    }

    protected DatabaseCreator getDatabaseCreator() {
        return new DatabaseCreator(dataSource, tablePrefix, getClass());
    }

    @Override
    public void setJobMapper(JobMapper jobMapper) {
        this.jobMapper = jobMapper;
    }

    @Override
    public void announceBackgroundJobServer(BackgroundJobServerStatus serverStatus) {
        backgroundJobServerTable().announce(serverStatus);
    }

    @Override
    public boolean signalBackgroundJobServerAlive(BackgroundJobServerStatus serverStatus) {
        return backgroundJobServerTable().signalServerAlive(serverStatus);
    }

    @Override
    public void signalBackgroundJobServerStopped(BackgroundJobServerStatus serverStatus) {
        backgroundJobServerTable().signalServerStopped(serverStatus);
    }

    @Override
    public List<BackgroundJobServerStatus> getBackgroundJobServers() {
        return backgroundJobServerTable().getAll();
    }

    @Override
    public UUID getLongestRunningBackgroundJobServerId() {
        return backgroundJobServerTable().getLongestRunningBackgroundJobServerId();
    }

    @Override
    public int removeTimedOutBackgroundJobServers(Instant heartbeatOlderThan) {
        return backgroundJobServerTable()
                .removeAllWithLastHeartbeatOlderThan(heartbeatOlderThan);
    }

    @Override
    public void saveMetadata(JobRunrMetadata metadata) {
        metadataTable().save(metadata);
        notifyMetadataChangeListeners();
    }

    @Override
    public List<JobRunrMetadata> getMetadata(String key) {
        return metadataTable().getAll(key);
    }

    @Override
    public JobRunrMetadata getMetadata(String key, String owner) {
        return metadataTable().get(key, owner);
    }

    @Override
    public void deleteMetadata(String key) {
        final int amountDeleted = metadataTable().deleteByKey(key);
        notifyMetadataChangeListeners(amountDeleted > 0);
    }

    @Override
    public Job getJobById(UUID id) {
        return jobTable()
                .selectJobById(id)
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    @Override
    public Job save(Job jobToSave) {
        final Job save = jobTable().save(jobToSave);
        notifyJobStatsOnChangeListeners();
        return save;
    }

    @Override
    public int deletePermanently(UUID id) {
        final int amountDeleted = jobTable().deletePermanently(id);
        notifyJobStatsOnChangeListenersIf(amountDeleted > 0);
        return amountDeleted;
    }

    @Override
    public List<Job> save(List<Job> jobs) {
        final List<Job> savedJobs = jobTable().save(jobs);
        notifyJobStatsOnChangeListenersIf(!jobs.isEmpty());
        return savedJobs;
    }

    @Override
    public List<Job> getJobs(StateName state, PageRequest pageRequest) {
        return jobTable()
                .selectJobsByState(state, pageRequest);
    }

    @Override
    public List<Job> getJobs(StateName state, Instant updatedBefore, PageRequest pageRequest) {
        return jobTable()
                .selectJobsByState(state, updatedBefore, pageRequest);
    }

    @Override
    public List<Job> getScheduledJobs(Instant scheduledBefore, PageRequest pageRequest) {
        return jobTable()
                .selectJobsScheduledBefore(scheduledBefore, pageRequest);
    }

    @Override
    public Long countJobs(StateName state) {
        return jobTable()
                .countJobs(state);
    }

    @Override
    public Page<Job> getJobPage(StateName state, PageRequest pageRequest) {
        long count = countJobs(state);
        if (count > 0) {
            List<Job> jobs = getJobs(state, pageRequest);
            return new Page<>(count, jobs, pageRequest);
        }
        return new Page<>(0, new ArrayList<>(), pageRequest);
    }

    @Override
    public int deleteJobsPermanently(StateName state, Instant updatedBefore) {
        final int amountDeleted = jobTable().deleteJobsByStateAndUpdatedBefore(state, updatedBefore);
        notifyJobStatsOnChangeListenersIf(amountDeleted > 0);
        return amountDeleted;
    }

    @Override
    public Set<String> getDistinctJobSignatures(StateName... states) {
        return jobTable()
                .getDistinctJobSignatures(states);
    }

    @Override
    public boolean exists(JobDetails jobDetails, StateName... states) {
        return jobTable()
                .exists(jobDetails, states);
    }

    @Override
    public boolean recurringJobExists(String recurringJobId, StateName... states) {
        return jobTable()
                .recurringJobExists(recurringJobId, states);
    }

    @Override
    public RecurringJob saveRecurringJob(RecurringJob recurringJob) {
        return recurringJobTable()
                .save(recurringJob);
    }

    @Override
    public List<RecurringJob> getRecurringJobs() {
        return recurringJobTable()
                .selectAll();
    }

    @Override
    public int deleteRecurringJob(String id) {
        return recurringJobTable()
                .deleteById(id);
    }

    @Override
    public JobStats getJobStats() {
        Instant instant = Instant.now();
        return Sql.forType(JobStats.class)
                .using(dataSource, dialect, tablePrefix, "jobrunr_jobs_stats")
                .withOrderLimitAndOffset("total ASC", 1, 0)
                .select("* from jobrunr_jobs_stats")
                .map(resultSet -> toJobStats(resultSet, instant))
                .findFirst()
                .orElse(JobStats.empty()); //why: because oracle returns nothing
    }

    @Override
    public void publishTotalAmountOfSucceededJobs(int amount) {
        Sql.withoutType()
                .using(dataSource, dialect, tablePrefix, "jobrunr_metadata")
                .with("id", "succeeded-jobs-counter-cluster")
                .with("amount", amount)
                .update("jobrunr_metadata set value = cast((cast(cast(value as char(10)) as decimal) + :amount) as char(10)) where id = :id");
        //why the 3 casts: to be compliant with all DB servers
    }

    private JobStats toJobStats(SqlResultSet resultSet, Instant instant) {
        return new JobStats(
                instant,
                resultSet.asLong("total"),
                resultSet.asLong("awaiting"),
                resultSet.asLong("scheduled"),
                resultSet.asLong("enqueued"),
                resultSet.asLong("processing"),
                resultSet.asLong("failed"),
                resultSet.asLong("succeeded"),
                resultSet.asLong("allTimeSucceeded"),
                resultSet.asLong("deleted"),
                resultSet.asInt("nbrOfRecurringJobs"),
                resultSet.asInt("nbrOfBackgroundJobServers")
        );
    }

    protected JobTable jobTable() {
        return new JobTable(dataSource, dialect, tablePrefix, jobMapper);
    }

    protected RecurringJobTable recurringJobTable() {
        return new RecurringJobTable(dataSource, dialect, tablePrefix, jobMapper);
    }

    protected BackgroundJobServerTable backgroundJobServerTable() {
        return new BackgroundJobServerTable(dataSource, dialect, tablePrefix);
    }

    protected MetadataTable metadataTable() {
        return new MetadataTable(dataSource, dialect, tablePrefix);
    }

}
