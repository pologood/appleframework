package com.appleframework.distributed.zookeeper.id;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.atomic.AtomicValue;
import org.apache.curator.framework.recipes.atomic.DistributedAtomicLong;
import org.apache.curator.framework.recipes.atomic.PromotedToLock;
import org.apache.curator.retry.BoundedExponentialBackoffRetry;
import org.apache.curator.retry.RetryNTimes;

/**
 * This id generator utilizes Zookeeper (http://zookeeper.apache.org/) to
 * generate serial IDs.
 * 
 * <p>
 * Persistency: IDs generated by this id-generator are persistent (backed by
 * Zookeeper).
 * </p>
 * 
 * @author Thanh Nguyen <btnguyen2k@gmail.com>
 * @since 0.1.0
 */
public class ZookeeperIdGenerator extends SerialIdGenerator implements IdentityGenerator {

    /**
     * Helper method to obtain {@link ZookeeperIdGenerator}.
     * 
     * @param zkConnString
     * @return
     */
    public static ZookeeperIdGenerator getInstance(final String zkConnString) {
        try {
            ZookeeperIdGenerator idGen = (ZookeeperIdGenerator) idGenerators.get(zkConnString,
                    new Callable<SerialIdGenerator>() {
                        @Override
                        public SerialIdGenerator call() throws Exception {
                            ZookeeperIdGenerator idGen = new ZookeeperIdGenerator();
                            idGen.setZookeeperConnString(zkConnString).setConcurrency(4).init();
                            return idGen;
                        }
                    });
            return idGen;
        } catch (ExecutionException e) {
            return null;
        }
    }

    private CuratorFramework curatorFramework;
    private String zkConnString = "localhost:2181";
    private Semaphore semaphore;
    private int concurrency = 4;

    public String getZookeeperConnString() {
        return zkConnString;
    }

    public ZookeeperIdGenerator setZookeeperConnString(String zkConnString) {
        this.zkConnString = zkConnString;
        return this;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public ZookeeperIdGenerator setConcurrency(int concurrency) {
        this.concurrency = concurrency;
        if (this.concurrency < 1) {
            this.concurrency = 1;
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ZookeeperIdGenerator init() {
        super.init();
        semaphore = new Semaphore(concurrency, true);
        RetryPolicy retryPolicy = new RetryNTimes(1, 2000);
        curatorFramework = CuratorFrameworkFactory.newClient(zkConnString, 3600000, 3000, retryPolicy);
        curatorFramework.start();
        return this;
    }

    public void destroy() {
        try {
            if (curatorFramework != null) {
                curatorFramework.close();
            }
        } catch (Exception e) {
        } finally {
            curatorFramework = null;
        }
        super.destroy();
    }

    /**
     * Calculates path for ID and LOCK from a namespace.
     * 
     * @param namespace
     * @return
     * @since 0.2.0
     */
    private static String[] calcPathIdAndPathLock(final String namespace) {
        String pathId = "/" + namespace.replaceAll("^\\/+", "").replaceAll("\\/+$", "");
        String pathLock = pathId + "/lock";
        return new String[] { pathId, pathLock };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long nextId(final String namespace) {
        final String[] paths = calcPathIdAndPathLock(namespace);
        final String pathId = paths[0];
        final String pathLock = paths[1];

        RetryPolicy retryPolicyMutex = new BoundedExponentialBackoffRetry(10, 1000, 5);
        PromotedToLock promotedToLock = PromotedToLock.builder().retryPolicy(retryPolicyMutex).lockPath(pathLock).build();
        RetryPolicy retryPolicyOptimistic = new RetryNTimes(3, 100);
        DistributedAtomicLong dal = new DistributedAtomicLong(curatorFramework, pathId, retryPolicyOptimistic, promotedToLock);
        semaphore.acquireUninterruptibly();
        try {
            AtomicValue<Long> value = dal.increment();
            if (value != null && value.succeeded()) {
                return value.postValue();
            }
            return -1;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long currentId(final String namespace) {
        final String[] paths = calcPathIdAndPathLock(namespace);
        final String pathId = paths[0];
        final String pathLock = paths[1];

        RetryPolicy retryPolicyMutex = new BoundedExponentialBackoffRetry(10, 1000, 5);
        PromotedToLock promotedToLock = PromotedToLock.builder().retryPolicy(retryPolicyMutex).lockPath(pathLock).build();
        RetryPolicy retryPolicyOptimistic = new RetryNTimes(3, 100);
        DistributedAtomicLong dal = new DistributedAtomicLong(curatorFramework, pathId, retryPolicyOptimistic, promotedToLock);
        try {
            AtomicValue<Long> value = dal.get();
            if (value != null && value.succeeded()) {
                return value.postValue();
            }
            throw new RuntimeException("Operation was not successful!");
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @since 0.4.0
     */
    @Override
    public boolean setValue(final String namespace, final long value) {
        final String[] paths = calcPathIdAndPathLock(namespace);
        final String pathId = paths[0];
        final String pathLock = paths[1];

        RetryPolicy retryPolicyMutex = new BoundedExponentialBackoffRetry(10, 1000, 5);
        PromotedToLock promotedToLock = PromotedToLock.builder().retryPolicy(retryPolicyMutex).lockPath(pathLock).build();
        RetryPolicy retryPolicyOptimistic = new RetryNTimes(3, 100);
        DistributedAtomicLong dal = new DistributedAtomicLong(curatorFramework, pathId, retryPolicyOptimistic, promotedToLock);
        semaphore.acquireUninterruptibly();
        try {
            dal.forceSet(value);
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }
}