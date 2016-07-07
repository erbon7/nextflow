/*
 * Copyright (c) 2013-2016, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2016, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.executor
import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.processor.TaskMonitor
import nextflow.processor.TaskPollingMonitor
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.util.Duration
import org.apache.commons.lang.StringUtils
/**
 * Generic task processor executing a task through a grid facility
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
abstract class AbstractGridExecutor extends Executor {

    protected Duration queueInterval

    /**
     * Initialize the executor class
     */
    void init() {
        super.init()
        queueInterval = session.getQueueStatInterval(name)
        log.debug "Creating executor '$name' > queue-stat-interval: ${queueInterval}"
    }

    /**
     * Create a a queue holder for this executor
     * @return
     */
    def TaskMonitor createTaskMonitor() {
        return TaskPollingMonitor.create(session, name, 100, Duration.of('1 sec'))
    }

    /*
     * Prepare and launch the task in the underlying execution platform
     */
    def GridTaskHandler createTaskHandler(TaskRun task) {
        assert task
        assert task.workDir

        return new GridTaskHandler(task, this)
    }

    protected BashWrapperBuilder createBashWrapperBuilder(TaskRun task) {
        // creates the wrapper script
        final builder = new BashWrapperBuilder(task)
        // job directives headers
        builder.headerScript = getHeaders(task)
        return builder
    }

    /**
     * Defines the jobs directive headers
     *
     * @param task
     * @return A multi-line string containing the job directives
     */
    String getHeaders( TaskRun task ) {

        final token = getHeaderToken()
        def result = new StringBuilder()
        def header = new ArrayList(2)
        def dir = getDirectives(task)
        def len = dir.size()-1
        for( int i=0; i<len; i+=2) {
            def opt = dir[i]
            def val = dir[i+1]
            if( opt ) header.add(opt)
            if( val ) header.add(wrapHeader(val))

            if( header ) {
                result << token << ' ' << header.join(' ') << '\n'
            }

            header.clear()
        }

        return result.toString()
    }

    /**
     * @return String used to declare job directives in the job script wrapper
     */
    abstract protected String getHeaderToken()

    /**
     * @param task The current task object
     * @return A list of directives for this task used for the job submission
     */
    final List<String> getDirectives(TaskRun task) {
        getDirectives(task, new ArrayList<String>())
    }

    /**
     * @param task The current task object
     * @param initial An initial list of directives
     * @return A list of directives for this task used for the job submission
     */
    abstract protected List<String> getDirectives(TaskRun task, List<String> initial)

    /**
     * Given a task returns a *clean* name used to submit the job to the grid engine.
     * That string must not contain blank or special shell characters e.g. parenthesis, etc
     *
     * @param task A {@code TaskRun} instance
     * @return A string that represent to submit job name
     */
    protected String getJobNameFor(TaskRun task) {

        // -- check for a custom `jobName` defined in the nextflow config file
        def customName = resolveCustomJobName(task)
        if( customName )
            return customName

        // -- if not available fallback on the custom naming strategy
        final BLANK = ' ' as char
        final COLON = ':' as char
        final result = new StringBuilder("nf-")
        final name = task.getName()
        for( int i=0; i<name.size(); i++ ) {
            def ch = name.charAt(i)
            result.append( ch == BLANK || ch == COLON ? '_' : ch )
        }
        result.toString()
    }

    /**
     * Resolve the `jobName` property defined in the nextflow config file
     *
     * @param task
     * @return
     */
    @PackageScope
    String resolveCustomJobName(TaskRun task) {
        try {
            def custom = (Closure)session.getExecConfigProp(name, 'jobName', null)
            if( !custom )
                return null

            def ctx = [ (TaskProcessor.TASK_CONTEXT_PROPERTY_NAME): task.config ]
            custom.cloneWith(ctx).call()?.toString()
        }
        catch( Exception e ) {
            log.debug "Unable to resolve job custom name", e
            return null
        }
    }

    /**
     * Build up the platform native command line used to submit the job wrapper
     * execution request to the underlying grid, e.g. {@code qsub -q something script.job}
     *
     * @param task The task instance descriptor
     * @return A list holding the command line
     */
    abstract List<String> getSubmitCommandLine(TaskRun task, Path scriptFile)

    /**
     * Defines how script is run the by the grid-engine.
     * @return When {@code true} the launcher script is piped over the submit tool stdin stream,
     *  if {@code false} is specified as an argument on the command line
     */
    protected boolean pipeLauncherScript() { false }

    /**
     * Given the string returned the by grid submit command, extract the process handle i.e. the grid jobId
     */
    abstract parseJobId( String text );

    /**
     * Kill a grid job
     *
     * @param jobId The ID of the job to kill
     */
    void killTask( def jobId )  {
        new ProcessBuilder(killTaskCommand(jobId)).start()
    }

    /**
     * The command to be used to kill a grid job
     * @param jobId The job ID to be kill
     * @return The command line to be used to kill the specified job
     */
    protected List<String> killTaskCommand(def jobId) {
        final result = [ getKillCommand() ]
        if( jobId instanceof Collection ) {
            jobId.each { result.add(it.toString()) }
            log.trace "Kill command: ${result}"
        }
        else {
            result.add(jobId.toString())
        }
        return result
    }

    protected abstract String getKillCommand()

    /**
     * Status as returned by the grid engine
     */
    static protected enum QueueStatus { PENDING, RUNNING, HOLD, ERROR, DONE, UNKNOWN }

    /**
     * @return The status for all the scheduled and running jobs
     */
    Map<?,QueueStatus> getQueueStatus(queue) {

        List cmd = queueStatusCommand(queue)
        if( !cmd ) return null

        try {
            if(log.isTraceEnabled())
            log.trace "Getting grid queue status: ${cmd.join(' ')}"

            def process = new ProcessBuilder(cmd).start()
            def result = process.text
            process.waitForOrKill( 10 * 1000 )
            def exit = process.exitValue()

            if(log.isTraceEnabled())
            log.trace "${name.toUpperCase()} status result > exit: $exit\n$result\n"

            return ( exit == 0 ) ? parseQueueStatus( result ) : null

        }
        catch( Exception e ) {
            log.warn "Unable to fetch queue status -- See the log file for details", e
            return null
        }

    }

    @PackageScope
    String dumpQueueStatus() {
        def result = new StringBuilder()
        fQueueStatus?.each { k, v ->
            result << '  job: ' << StringUtils.leftPad(k?.toString(),6) << ': ' << v?.toString() << '\n'
        }
        return result.toString()
    }

    /**
     * @param queue The command for which the status of jobs has be to read
     * @return The command line to be used to retried the job statuses
     */
    protected abstract List<String> queueStatusCommand(queue)

    /**
     * Parse the command stdout produced by the command line {@code #queueStatusCommand}
     * @param text
     * @return
     */
    protected abstract Map<?,QueueStatus> parseQueueStatus( String text )

    /**
     * Store jobs status
     */
    protected Map<Object,QueueStatus> fQueueStatus = null

    /**
     * Verify that a job in a 'active' state i.e. RUNNING or HOLD
     *
     * @param jobId The job for which verify the status
     * @return {@code true} if the job is in RUNNING or HOLD status, or even if it is temporarily unable
     *  to retrieve the job status for some
     */
    public boolean checkActiveStatus( jobId, queue ) {

        // -- fetch the queue status
        fQueueStatus = (Map<Object,QueueStatus>)queueInterval.throttle(null) { getQueueStatus(queue) }
        if( fQueueStatus == null ) { // no data is returned, so return true
            log.trace "Queue status map is null -- return true"
            return true
        }

        if( log.isTraceEnabled() )
            log.trace "Queue status:\n" + dumpQueueStatus()

        if( !fQueueStatus.containsKey(jobId) ) {
            log.trace "Queue status map does not contain jobId: `$jobId`"
            return false
        }

        final result = fQueueStatus[jobId] == QueueStatus.RUNNING || fQueueStatus[jobId] == QueueStatus.HOLD
        log.trace "JobId `$jobId` active status: $result"
        return result
    }

    protected String wrapHeader( String str ) { str }


}

