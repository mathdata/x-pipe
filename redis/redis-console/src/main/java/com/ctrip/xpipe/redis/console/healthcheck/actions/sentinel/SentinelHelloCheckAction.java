package com.ctrip.xpipe.redis.console.healthcheck.actions.sentinel;

import com.ctrip.xpipe.concurrent.AbstractExceptionLogTask;
import com.ctrip.xpipe.exception.ExceptionUtils;
import com.ctrip.xpipe.redis.console.config.ConsoleDbConfig;
import com.ctrip.xpipe.redis.console.healthcheck.HealthCheckActionController;
import com.ctrip.xpipe.redis.console.healthcheck.RedisHealthCheckInstance;
import com.ctrip.xpipe.redis.console.healthcheck.RedisInstanceInfo;
import com.ctrip.xpipe.redis.console.healthcheck.leader.AbstractLeaderAwareHealthCheckAction;
import com.ctrip.xpipe.redis.console.healthcheck.nonredis.cluster.impl.DefaultClusterHealthMonitorManager;
import com.ctrip.xpipe.redis.console.healthcheck.session.RedisSession;
import com.ctrip.xpipe.redis.console.migration.status.ClusterStatus;
import com.ctrip.xpipe.redis.console.service.ClusterService;
import com.ctrip.xpipe.utils.VisibleForTesting;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author chen.zhu
 * <p>
 * Oct 09, 2018
 */
public class SentinelHelloCheckAction extends AbstractLeaderAwareHealthCheckAction {

    private static final Logger logger = LoggerFactory.getLogger(SentinelHelloCheckAction.class);

    protected static int SENTINEL_COLLECT_INFO_INTERVAL = 5000;

    public static final String HELLO_CHANNEL = "__sentinel__:hello";

    private Set<SentinelHello> hellos = Sets.newConcurrentHashSet();

    private ConsoleDbConfig consoleDbConfig;

    private ClusterService clusterService;

    private List<HealthCheckActionController> controllers = new ArrayList<>();

    private static final int SENTINEL_CHECK_BASE_INTERVAL = 10000;

    private Throwable subError;

    protected long lastStartTime = System.currentTimeMillis();

    public SentinelHelloCheckAction(ScheduledExecutorService scheduled, RedisHealthCheckInstance instance,
                                    ExecutorService executors, ConsoleDbConfig consoleDbConfig, ClusterService clusterService) {
        super(scheduled, instance, executors);
        this.consoleDbConfig = consoleDbConfig;
        this.clusterService = clusterService;
    }

    @Override
    protected void doTask() {
        if(!shouldStart()) {
            return;
        }

        lastStartTime = System.currentTimeMillis();
        RedisInstanceInfo info = getActionInstance().getRedisInstanceInfo();
        if (instance.getRedisInstanceInfo().isInActiveDc()) {
            logger.info("[doTask][{}-{}] in active dc, redis {}", info.getClusterId(), info.getShardId(), instance.getEndpoint());
        }

        subError = null;
        getActionInstance().getRedisSession().subscribeIfAbsent(HELLO_CHANNEL, new RedisSession.SubscribeCallback() {
            @Override
            public void message(String channel, String message) {
                executors.execute(new Runnable() {
                    @Override
                    public void run() {
                        SentinelHello hello = SentinelHello.fromString(message);
                        hellos.add(hello);
                    }
                });
            }

            @Override
            public void fail(Throwable e) {
                if (ExceptionUtils.isStackTraceUnnecessary(e)) {
                    logger.error("[sub-failed][{}] {}", getActionInstance().getRedisInstanceInfo().getHostPort(), e.getMessage());
                } else {
                    logger.error("[sub-failed][{}]", getActionInstance().getRedisInstanceInfo().getHostPort(), e);
                }
                subError = e;
            }
        });
        scheduled.schedule(new AbstractExceptionLogTask() {
            @Override
            protected void doRun() throws Exception {
                processSentinelHellos();
            }
        }, SENTINEL_COLLECT_INFO_INTERVAL, TimeUnit.MILLISECONDS);
    }

    @Override
    protected Logger getHealthCheckLogger() {
        return logger;
    }

    @VisibleForTesting
    protected void processSentinelHellos() {
        getActionInstance().getRedisSession().closeSubscribedChannel(HELLO_CHANNEL);
        SentinelActionContext actionContext = null != subError ? new SentinelActionContext(getActionInstance(), subError) :
                new SentinelActionContext(getActionInstance(), hellos);
        notifyListeners(actionContext);
        hellos = Sets.newConcurrentHashSet();
    }

    private boolean shouldStart() {
        long current = System.currentTimeMillis();
        if( current - lastStartTime < getIntervalMilli()){
            logger.debug("[generatePlan][too quick {}, quit]", current - lastStartTime);
            return false;
        }

        String cluster = getActionInstance().getRedisInstanceInfo().getClusterId();
        if (!consoleDbConfig.shouldSentinelCheck(cluster, false)) {
            logger.warn("[doTask][BackupDc] cluster is in sentinel check whitelist, quit");

            return false;
        }

        String clusterStatus = clusterService.find(getActionInstance().getRedisInstanceInfo().getClusterId()).getStatus();
        if (!ClusterStatus.isSameClusterStatus(clusterStatus, ClusterStatus.Normal)) {
            logger.warn("[shouldStart][{}] in migration, stop check", getActionInstance().getRedisInstanceInfo().getClusterId());
            return false;
        }

        for (HealthCheckActionController controller : controllers) {
            if (!controller.shouldCheck(instance)) {
                RedisInstanceInfo redisInfo = getActionInstance().getRedisInstanceInfo();
                logger.debug("[shouldStart][{}-{}] {} {} controller {} refuse check",
                        redisInfo.getClusterId(), redisInfo.getShardId(),
                        redisInfo.isInActiveDc() ? "activeDc" : "backupDc", redisInfo.getDcId(),
                        controller.getClass().getSimpleName());
                return false;
            }
        }

        return consoleDbConfig.isSentinelAutoProcess();
    }

    @Override
    protected int getBaseCheckInterval() {
        return SENTINEL_CHECK_BASE_INTERVAL;
    }

    protected int getIntervalMilli() {
        return instance.getHealthCheckConfig().getSentinelCheckIntervalMilli();
    }

    @Override
    public void addController(HealthCheckActionController controller) {
        this.controllers.add(controller);
    }
}
