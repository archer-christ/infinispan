package org.infinispan.interceptors.xsite;

import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.DDSequentialInterceptor;
import org.infinispan.transaction.impl.LocalTransaction;
import org.infinispan.transaction.impl.TransactionTable;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.infinispan.xsite.BackupSender;

import java.util.concurrent.CompletableFuture;

/**
 * @author Mircea Markus
 * @since 5.2
 */
public class BaseBackupInterceptor extends DDSequentialInterceptor {

   protected BackupSender backupSender;
   protected TransactionTable txTable;

   private static final Log log = LogFactory.getLog(BaseBackupInterceptor.class);
   
   @Inject
   void init(BackupSender sender, TransactionTable txTable) {
      this.backupSender = sender;
      this.txTable = txTable;
   }

   @Override
   public final CompletableFuture<Void> visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      return handleMultipleKeysWriteCommand(ctx, command);
   }

   protected CompletableFuture<Void> handleMultipleKeysWriteCommand(InvocationContext ctx, WriteCommand command) throws Throwable {
      if (!ctx.isOriginLocal() || skipXSiteBackup(command)) {
         return ctx.continueInvocation();
      }
      Object result = ctx.forkInvocationSync(command);
      backupSender.processResponses(backupSender.backupWrite(command), command);
      return ctx.shortCircuit(result);
   }
   
   protected boolean isTxFromRemoteSite(GlobalTransaction gtx) {
      LocalTransaction remoteTx = txTable.getLocalTransaction(gtx);
      return remoteTx != null && remoteTx.isFromRemoteSite();
   }

   protected boolean shouldInvokeRemoteTxCommand(TxInvocationContext ctx) {
      // ISPN-2362: For backups, we should only replicate to the remote site if there are modifications to replay.
      boolean shouldBackupRemotely = ctx.isOriginLocal() && ctx.hasModifications() &&
            !ctx.getCacheTransaction().isFromStateTransfer();
      getLog().tracef("Should backup remotely? %s", shouldBackupRemotely);
      return shouldBackupRemotely;
   }

   protected final boolean skipXSiteBackup(FlagAffectedCommand command) {
      return command.hasFlag(Flag.SKIP_XSITE_BACKUP);
   }
   
   protected Log getLog() {
      return log;
   }
}
