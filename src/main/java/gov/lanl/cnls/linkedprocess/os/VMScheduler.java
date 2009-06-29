package gov.lanl.cnls.linkedprocess.os;

import gov.lanl.cnls.linkedprocess.LinkedProcess;
import org.apache.log4j.Logger;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Author: josh
 * Date: Jun 24, 2009
 * Time: 2:15:27 PM
 */
public class VMScheduler {
    private static final Logger LOGGER
            = LinkedProcess.getLogger(VMScheduler.class);

    private final PollBlockingQueue<VMWorker> workerQueue;
    private final Set<VMWorker> idleWorkerPool;
    private final Map<String, VMWorker> workersByJID;
    private final ScriptEngineManager manager = new ScriptEngineManager();
    private final int maxWorkers;
    private final VMResultHandler resultHandler;
    private SchedulerStatus status;

    public enum SchedulerStatus {
        ACTIVE, ACTIVE_FULL, TERMINATED
    }

    public enum VMStatus {
        IN_PROGRESS, DOES_NOT_EXIST
    }

    // TODO: how about a "queued" status for jobs?
    public enum JobStatus {
        IN_PROGRESS, DOES_NOT_EXIST
    }

    /**
     * Creates a new virtual machine scheduler.
     * @param resultHandler a handler for results produced by the scheduler
     */
    public VMScheduler(final VMResultHandler resultHandler) {
        LOGGER.info("instantiating VMScheduler");
        
        this.resultHandler = resultHandler;

        workerQueue = new PollBlockingQueue<VMWorker>();
        idleWorkerPool = new HashSet<VMWorker>();
        workersByJID = new HashMap<String, VMWorker>();

        status = SchedulerStatus.ACTIVE;

        Properties props = LinkedProcess.getProperties();

        maxWorkers = new Integer(props.getProperty(
                LinkedProcess.MAX_VIRTUAL_MACHINES_PER_SCHEDULER));

        long timeSlice = new Long(props.getProperty(
                LinkedProcess.ROUND_ROBIN_TIME_SLICE));

        // A single source for workers.
        VMWorkerSource source = createWorkerSource();

        int n = new Integer(props.getProperty(
                LinkedProcess.MAX_CONCURRENT_WORKER_THREADS));
        VMSequencer[] sequencers = new VMSequencer[n];
        for (int i = 0; i < n; i++) {
            sequencers[i] = new VMSequencer(source, timeSlice);
        }
    }

    /**
     * Adds a job to the queue of the given machine.
     *
     * @param machineJID the JID of the virtual machine to execute the job
     * @param job        the job to execute
     * @throws ServiceRefusedException if, for any reason, the job cannot be
     *                                 accepted
     */
    public synchronized void addJob(final String machineJID,
                                    final Job job) throws ServiceRefusedException {
        VMWorker w = getWorkerByJID(machineJID);

        // FIXME: this call may block for as long as one timeslice.
        //        This wait could probably be eliminated.
        w.addJob(job);

        enqueueWorker(w);
    }

    /**
     * Removes or cancels a job.
     *
     * @param machineJID the machine who was to have received the job
     * @param jobID      the ID of the specific job to be removed
     * @throws ServiceRefusedException if the job is not found
     */
    public synchronized void removeJob(final String machineJID,
                                       final String jobID) throws ServiceRefusedException {
        VMWorker w = getWorkerByJID(machineJID);

        // FIXME: this call may block for as long as one timeslice.
        //        This wait could probably be eliminated.
        w.cancelJob(jobID);
    }

    /**
     * Creates a new virtual machine.
     *
     * @param machineJID the intended JID of the virtual machine
     * @param scriptType the type of virtual machine to create
     * @throws ServiceRefusedException if, for any reason, the machine cannot be created
     */
    public synchronized void addMachine(final String machineJID,
                                        final String scriptType) throws ServiceRefusedException {
        LOGGER.info("attempting to add machine of type " + scriptType + " with JID '" + machineJID + "'");

        if (SchedulerStatus.ACTIVE_FULL == status) {
            throw new ServiceRefusedException("too many active virtual machines");
        }

        if (null == machineJID || 0 == machineJID.length()) {
            throw new IllegalArgumentException("null or empty machine ID");
        }

        // TODO: check whether the scriptType is one of the allowed types
        if (null == scriptType || 0 == scriptType.length()) {
            throw new IllegalArgumentException("null or empty virtual machine scriptType");
        }

        if (null != workersByJID.get(machineJID)) {
            throw new ServiceRefusedException("machine with ID '" + machineJID + "' already exists");
        }

        ScriptEngine engine = manager.getEngineByName(scriptType);
        if (null == engine) {
            throw new ServiceRefusedException("unsupported script type: " + scriptType);
        }

        VMWorker w = new VMWorker(engine, resultHandler);

        workersByJID.put(machineJID, w);
        if (maxWorkers == workersByJID.size()) {
            status = SchedulerStatus.ACTIVE_FULL;
        }

        idleWorkerPool.add(w);
    }

    /**
     * Destroys an already-created virtual machine.
     *
     * @param machineJID the JID of the virtual machine to destroy
     * @throws ServiceRefusedException if, for any reason, the virtual machine
     *                                 cannot be destroyed
     */
    public synchronized void removeMachine(final String machineJID) throws ServiceRefusedException {
        VMWorker w = getWorkerByJID(machineJID);

        workersByJID.remove(machineJID);
        synchronized (workerQueue) {
            workerQueue.remove(w);
        }

        idleWorkerPool.remove(w);

        w.terminate();

        if (maxWorkers > workersByJID.size()) {
            status = SchedulerStatus.ACTIVE;
        }
    }

    /**
     * @return the status of this scheduler
     */
    public synchronized SchedulerStatus getFarmPresence() {
        return status;
    }

    /**
     * @param machineJID the JID of the virtual machine of interest
     * @return the status of the given virtual machine
     */
    public synchronized VMStatus getVMPresence(final String machineJID) {
        VMWorker w = workersByJID.get(machineJID);
        return (null == w)
                ? VMStatus.DOES_NOT_EXIST
                : VMStatus.IN_PROGRESS;
    }

    /**
     * @param machineJID the JID of the machine to execute the job
     * @param iqID the ID of the job of interest
     * @return the status of the given job
     */
    public synchronized JobStatus getJobStatus(final String machineJID,
                                               final String iqID) {
        VMWorker w = workersByJID.get(machineJID);
        return w.jobExists(iqID)
                ? JobStatus.IN_PROGRESS
                : JobStatus.DOES_NOT_EXIST;
    }

    /**
     * Shuts down all active virtual machines and cancels all jobs.
     */
    public synchronized void shutDown() {
        workerQueue.clear();
        workerQueue.stopBlocking();

        for (VMWorker w : workersByJID.values()) {
            w.terminate();
        }

        status = SchedulerStatus.TERMINATED;
    }

    ////////////////////////////////////////////////////////////////////////////

    /*
    private VMResultHandler createResultHandler() {
        return new VMResultHandler() {
            public void handleResult(final JobResult result) {
                //To change body of implemented methods use File | Settings | File Templates.
            }
        };
    }      */

    private VMWorkerSource createWorkerSource() {
        return new VMWorkerSource() {
            public synchronized VMWorker getWorker() {
                return workerQueue.poll();
            }
        };
    }

    private void enqueueWorker(final VMWorker w) {
        // If the worker is in the idle pool, add it to the queue instead.
        // Otherwise, don't move it.
        if (idleWorkerPool.contains(w)) {
            idleWorkerPool.remove(w);
            workerQueue.offer(w);
        }
    }

    private VMWorker getWorkerByJID(final String machineJID) throws ServiceRefusedException {
        VMWorker w = workersByJID.get(machineJID);

        if (null == w) {
            throw new ServiceRefusedException("no such machine: '" + machineJID + "'");
        }

        return w;
    }

    ////////////////////////////////////////////////////////////////////////////

    public interface VMResultHandler {
        void handleResult(JobResult result);
    }

    public interface VMWorkerSource {
        VMWorker getWorker();
    }
}
