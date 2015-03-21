package org.batfish.coordinator;

import java.util.UUID;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.logging.log4j.Logger;
import org.batfish.common.BatfishConstants.TaskStatus;
import org.batfish.common.CoordinatorConstants.WorkStatusCode;
import org.batfish.coordinator.queues.AzureQueue;
import org.batfish.coordinator.queues.MemoryQueue;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

// the design of this WorkQueueMgr is such that all synchronization sits here
// individual queues do not need to be synchronized

public class WorkQueueMgr {

   public enum QueueType {INCOMPLETE, COMPLETED}

   private WorkQueue _queueIncompleteWork;
   private WorkQueue _queueCompletedWork;

   Logger _logger = Main.initializeLogger();
   
   public WorkQueueMgr() {
      if (Main.getSettings().getQueueType() == WorkQueue.Type.azure) {
         String storageConnectionString = String.format(
               "DefaultEndpointsProtocol=%s;AccountName=%s;AccountKey=%s", Main
                     .getSettings().getStorageProtocol(), Main.getSettings()
                     .getStorageAccountName(), Main.getSettings()
                     .getStorageAccountKey());

         _queueCompletedWork = new AzureQueue(Main.getSettings()
               .getQueueCompletedWork(), storageConnectionString);
         _queueIncompleteWork = new AzureQueue(Main.getSettings()
               .getQueueIncompleteWork(), storageConnectionString);
      }
      else if (Main.getSettings().getQueueType() == WorkQueue.Type.memory) {
         _queueCompletedWork = new MemoryQueue();
         _queueIncompleteWork = new MemoryQueue();
      }
      else {
         _logger.fatal("unsupported queue type: " + Main.getSettings().getQueueType());
         System.exit(1);
      }
   }
   
   public JSONObject getStatusJson() throws JSONException {
      
      JSONObject jObject = new JSONObject();
      
      jObject.put("incomplete-works", _queueIncompleteWork.getLength()); 
      jObject.put("completed-works", _queueCompletedWork.getLength()); 
      
      return jObject;
   }

   public synchronized boolean queueUnassignedWork(QueuedWork work) throws Exception {
      
      QueuedWork previouslyQueuedWork = getWork(work.getId());
      
      if (previouslyQueuedWork != null) {
         throw new Exception("Duplicate id for work");
      }
      
      return _queueIncompleteWork.enque(work);
   }
   
   public synchronized QueuedWork getWork(UUID workId) {
      QueuedWork work = getWork(workId, QueueType.INCOMPLETE);
      if (work == null) {
         work = getWork(workId, QueueType.COMPLETED);
      }      
      return work;
   }
   
   private synchronized QueuedWork getWork(UUID workId, QueueType qType) {
      switch (qType) {
         case COMPLETED:
            return _queueCompletedWork.getWork(workId);
         case INCOMPLETE:
            return _queueIncompleteWork.getWork(workId);     
      }            
      return null;
   }

   public synchronized long getLength(QueueType qType) {
      switch (qType) {
      case COMPLETED:
         return _queueCompletedWork.getLength();
      case INCOMPLETE:
         return _queueIncompleteWork.getLength();
      }
      return -1;
   }

   public synchronized QueuedWork getWorkForAssignment() {
      
      for (QueuedWork work : _queueIncompleteWork) {
         if (work.getStatus() == WorkStatusCode.UNASSIGNED) {
            work.setStatus(WorkStatusCode.TRYINGTOASSIGN);
            return work;
         }         
      }
      
      return null;
   }
 
   public QueuedWork getWorkForChecking() {
      
      for (QueuedWork work : _queueIncompleteWork) {
         if (work.getStatus() == WorkStatusCode.ASSIGNED) {
            work.setStatus(WorkStatusCode.CHECKINGSTATUS);
            return work;
         }         
      }
      
      return null;
   }
   
   public synchronized void markAssignmentFailure(QueuedWork work) {
         work.setStatus(WorkStatusCode.UNASSIGNED);
   }

   public synchronized void markAssignmentSuccess(QueuedWork work, String assignedWorker) {
      work.setAssignment(assignedWorker);
   }

   public synchronized void makeWorkUnassigned(QueuedWork work) {
      work.setStatus(WorkStatusCode.UNASSIGNED);
   }

   public synchronized void processStatusCheckResult(QueuedWork work, TaskStatus status) {

      // {Unscheduled, InProgress, TerminatedNormally, TerminatedAbnormally,
      // Unknown, UnreachableOrBadResponse}

      switch (status) {
      case Unscheduled:
      case InProgress:
        work.setStatus(WorkStatusCode.ASSIGNED);
         work.recordTaskStatusCheckResult(status);
         break;
      case TerminatedNormally:
      case TerminatedAbnormally:
         // move the work to completed queue
         _queueIncompleteWork.delete(work);
         try {
            _queueCompletedWork.enque(work);
         }
         catch (Exception e) {
            String stackTrace = ExceptionUtils.getFullStackTrace(e);
            _logger.error("Could not put work on completed queue. Work = "
                  + work + "\nException = " + stackTrace);
         }
         work.setStatus((status == TaskStatus.TerminatedNormally) ? WorkStatusCode.TERMINATEDNORMALLY
               : WorkStatusCode.TERMINATEDABNORMALLY);
         work.recordTaskStatusCheckResult(status);
      case Unknown:
         // we mark this unassigned, so we try to schedule it again
         work.setStatus(WorkStatusCode.UNASSIGNED);
         work.clearAssignment();
      case UnreachableOrBadResponse:
         if (work.getLastTaskCheckedStatus() == TaskStatus.UnreachableOrBadResponse) {
            // if we saw the same thing last time around, free the task to be
            // scheduled elsewhere
            work.setStatus(WorkStatusCode.UNASSIGNED);
            work.clearAssignment();
         }
         else {
            work.setStatus(WorkStatusCode.ASSIGNED);
            work.recordTaskStatusCheckResult(status);
         }
         break;
      }
   }
}
 