package org.infinispan.distribution;

import org.testng.annotations.Test;

@Test(groups = "functional", testName = "distribution.DistSyncTxUnsafeFuncTest")
public class DistSyncTxUnsafeFuncTest extends DistSyncTxFuncTest {
   public DistSyncTxUnsafeFuncTest() {
      testRetVals = false;
      cleanup = CleanupPhase.AFTER_METHOD; // ensure any stale TXs are wiped
   }
}
