package org.infinispan.atomic;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.distribution.MagicKey;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.statetransfer.StateResponseCommand;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.tx.dld.ControlledRpcManager;
import org.infinispan.util.Util;
import org.testng.annotations.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static org.testng.AssertJUnit.assertEquals;

/**
 * Test modifications to an AtomicMap during state transfer in a replicated
 * cluster are consistent.
 *
 * @author Ryan Emerson
 * @author Dan Berindei
 * @since 5.2
 */
//@Test(groups = "functional", testName = "atomic.ReplAtomicMapStateTransferTest")
@Test(groups = "functional")
public abstract class BaseAtomicMapStateTransferTest extends MultipleCacheManagersTest {

    private final CacheMode cacheMode;
    private final TransactionMode transactionMode;

    protected BaseAtomicMapStateTransferTest(CacheMode cacheMode, TransactionMode transactionMode) {
        this.cacheMode = cacheMode;
        this.transactionMode = transactionMode;
    }

    @Override
    protected void createCacheManagers() throws Throwable {
        ConfigurationBuilder c = getConfigurationBuilder();
        createClusteredCaches(1, "atomic", c);
    }

    protected ConfigurationBuilder getConfigurationBuilder() {
        ConfigurationBuilder c = new ConfigurationBuilder();
        c.clustering().cacheMode(cacheMode);
        c.transaction().transactionMode(transactionMode);
        return c;
    }

    public final void testAtomicMapPutDuringJoin() throws Exception {
        Cache cache = cache(0, "atomic");
        ControlledRpcManager crm = new ControlledRpcManager(cache.getAdvancedCache().getRpcManager());
        TestingUtil.replaceComponent(cache, RpcManager.class, crm, true);

        MagicKey atomicMapKey = new MagicKey("atomicMapKey", cache);
        AtomicMap atomicMap = AtomicMapLookup.getAtomicMap(cache, atomicMapKey);
        atomicMap.put("key1", "value1");

        crm.blockBefore(StateResponseCommand.class);

        ConfigurationBuilder c = getConfigurationBuilder();
        final EmbeddedCacheManager joiner = addClusterEnabledCacheManager(c);
        Future<Cache> future = fork(new Callable<Cache>() {
            @Override
            public Cache call() throws Exception {
                return joiner.getCache("atomic");
            }
        });

        crm.waitForCommandToBlock();

        // Now we know state transfer will try to create an AtomicMap(key1=value1) on cache2
        // Insert another key in the atomic map, and check that cache2 has both keys after the state transfer
        atomicMap.put("key2", "value2");

        crm.stopBlocking();
        Cache cache2 = future.get();

        AtomicMap atomicMap2 = AtomicMapLookup.getAtomicMap(cache2, atomicMapKey);
        assertEquals(Util.asSet("key1", "key2"), atomicMap.keySet());
        assertEquals(Util.asSet("key1", "key2"), atomicMap2.keySet());

        cache.getAdvancedCache().getTransactionManager().begin();
        atomicMap.put("key3", "value3");
        atomicMap.put("key4", "value4");
        cache.getAdvancedCache().getTransactionManager().commit();

        assertEquals(Util.asSet("key1", "key2", "key3", "key4"), atomicMap.keySet());
        assertEquals(Util.asSet("key1", "key2", "key3", "key4"), atomicMap2.keySet());

        cache2.getAdvancedCache().getTransactionManager().begin();
        atomicMap2.put("key5", "value5");
        atomicMap2.put("key6", "value6");
        cache2.getAdvancedCache().getTransactionManager().commit();

        assertEquals(Util.asSet("key1", "key2", "key3", "key4", "key5", "key6"), atomicMap.keySet());
        assertEquals(Util.asSet("key1", "key2", "key3", "key4", "key5", "key6"), atomicMap2.keySet());
    }
}