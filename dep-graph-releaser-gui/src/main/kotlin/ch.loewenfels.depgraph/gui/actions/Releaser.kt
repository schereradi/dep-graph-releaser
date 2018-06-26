package ch.loewenfels.depgraph.gui.actions

import ch.loewenfels.depgraph.data.*
import ch.loewenfels.depgraph.data.maven.jenkins.JenkinsCommand
import ch.loewenfels.depgraph.gui.*
import ch.loewenfels.depgraph.gui.components.Menu
import ch.loewenfels.depgraph.gui.components.Pipeline
import ch.loewenfels.depgraph.gui.jobexecution.*
import ch.loewenfels.depgraph.gui.serialization.ModifiableState
import ch.tutteli.kbox.mapWithIndex
import org.w3c.dom.HTMLAnchorElement
import kotlin.browser.window
import kotlin.collections.set
import kotlin.js.Promise

class Releaser(
    defaultJenkinsBaseUrl: String,
    private val modifiableState: ModifiableState,
    private val menu: Menu
) {

    private val isOnSameHost: Boolean

    init {
        val prefix = window.location.protocol + "//" + window.location.hostname
        isOnSameHost = defaultJenkinsBaseUrl.startsWith(prefix)
    }

    fun release(jobExecutor: JobExecutor, jobExecutionDataFactory: JobExecutionDataFactory): Promise<Boolean> {
        warnIfNotOnSameHost()
        val rootProject = modifiableState.releasePlan.getRootProject()
        val paramObject = ParamObject(
            modifiableState.releasePlan, jobExecutor, jobExecutionDataFactory, rootProject, hashMapOf(), hashMapOf()
        )
        changeCursorToProgress()
        return release(paramObject)
            .finally {
                changeCursorBackToNormal()
                it ?: false
            }
    }

    private fun warnIfNotOnSameHost() {
        if (!isOnSameHost) {
            showWarning(
                "Remote publish server detected. We currently do not support to consume remote release.json." +
                    "\nThis means that we publish changes during the release process but will not change the location. Thus, please do not reload the page during the release process."
                , 8000
            )
        }
    }

    private fun release(paramObject: ParamObject): Promise<Boolean> {
        if (modifiableState.releasePlan.state != ReleaseState.IN_PROGRESS) {
            Pipeline.changeReleaseState(ReleaseState.IN_PROGRESS)
        }
        return releaseProject(paramObject)
            .then {
                val (result, newState) = checkProjectStates(paramObject)
                Pipeline.changeReleaseState(newState)
                quietSave(paramObject, verbose = false)
                    .catch { t ->
                        showThrowable(
                            Error(
                                "Could not save the release state (changed to $newState)." +
                                    "\nDo not reload if you want to continue using this pipeline and make sure the publisher works as expected." +
                                    "\nMake a change (e.g. change a Release Version) and try to save (will save the changed release state as well) -- do not forget to revert your change and save again.",
                                t
                            )
                        )
                    }
                result
            }
    }

    private fun checkProjectStates(paramObject: ParamObject): Pair<Boolean, ReleaseState> {
        val result = paramObject.projectResults.values.all {
            it === CommandState.Succeeded || it is CommandState.Deactivated || it === CommandState.Disabled
        }
        val newState = if (result) {
            ReleaseState.SUCCEEDED
        } else {
            checkForNoneFailedBug(paramObject)
            ReleaseState.FAILED
        }
        return result to newState
    }

    private fun checkForNoneFailedBug(paramObject: ParamObject) {
        if (paramObject.projectResults.values.none { it === CommandState.Failed }) {
            val erroneousProjects = paramObject.projectResults.entries
                .filter {
                    it.value !== CommandState.Failed && it.value !== CommandState.Succeeded &&
                        it.value !is CommandState.Deactivated && it.value !== CommandState.Disabled
                }
            if (erroneousProjects.isNotEmpty()) {
                showError(
                    """
                        |Seems like there is a bug since no command failed but not all commands are in status Succeeded.
                        |Please report a bug at $GITHUB_NEW_ISSUE - the following projects where affected:
                        |${erroneousProjects.joinToString("\n") { it.key.identifier }}
                    """.trimMargin()
                )
            }
        }
    }

    private fun releaseProject(paramObject: ParamObject): Promise<*> {
        return paramObject.withLockForProject {
            triggerNonReleaseCommandsInclSubmoduleCommands(paramObject).then { jobResult ->
                if (jobResult !== CommandState.Succeeded) return@then jobResult

                triggerReleaseCommands(paramObject).unsafeCast<CommandState>()
            }.then { jobResult ->
                paramObject.projectResults[paramObject.project.id] = jobResult
                if (jobResult !== CommandState.Succeeded) return@then jobResult

                val releasePlan = paramObject.releasePlan
                val allDependents = releasePlan.collectDependentsInclDependentsOfAllSubmodules(paramObject.project.id)
                updateStateWaiting(releasePlan, allDependents)
                releaseDependentProjects(allDependents, releasePlan, paramObject)
            }.catch { t ->
                paramObject.projectResults[paramObject.project.id] = CommandState.Failed
                if (t !== ReleaseFailure) throw t
            }
        }
    }

    private fun updateStateWaiting(releasePlan: ReleasePlan, allDependents: Set<Pair<ProjectId, ProjectId>>) {
        allDependents.forEach { (multiOrSubmoduleId, dependentId) ->
            val dependentProject = releasePlan.getProject(dependentId)
            dependentProject.commands.forEachIndexed { index, _ ->
                val state = Pipeline.getCommandState(dependentId, index)
                if (state is CommandState.Waiting && state.dependencies.contains(multiOrSubmoduleId)) {
                    (state.dependencies as MutableSet).remove(multiOrSubmoduleId)
                    if (state.dependencies.isEmpty()) {
                        Pipeline.changeStateOfCommand(
                            dependentProject, index, CommandState.Ready, Pipeline.STATE_READY
                        )
                    }
                }
            }
        }
    }

    private fun releaseDependentProjects(
        allDependents: HashSet<Pair<ProjectId, ProjectId>>,
        releasePlan: ReleasePlan,
        paramObject: ParamObject
    ): Promise<*> {
        val promises: List<Promise<*>> = allDependents
            .asSequence()
            .map { (_, dependentId) -> releasePlan.getProject(dependentId) }
            .filter { !it.isSubmodule }
            .toHashSet()
            .map { dependentProject ->
                releaseProject(ParamObject(paramObject, dependentProject))
            }
        // Stops as soon as a bug occurs in the execution of one job (not the same as a job-failure)
        // I think this is fine because we do not know if this bug has other consequences and thus it
        // might be better if we stop as early as possible in such cases.
        return Promise.all(promises.toTypedArray())
    }

    private fun triggerNonReleaseCommandsInclSubmoduleCommands(paramObject: ParamObject): Promise<CommandState> {
        return paramObject.project.commands
            .asSequence()
            .mapWithIndex()
            .filter { it.value !is ReleaseCommand }
            .doSequentially(mutableListOf()) { (index, command) ->
                createCommandPromise(paramObject, command, index)
            }.then { jobsResults ->
                paramObject.releasePlan.getSubmodules(paramObject.project.id)
                    .asSequence()
                    .doSequentially(jobsResults as MutableList<CommandState>) { submoduleId ->
                        triggerNonReleaseCommandsInclSubmoduleCommands(ParamObject(paramObject, submoduleId))
                    }
            }.then { jobsResults ->
                jobsResults.firstOrNull { it !== CommandState.Succeeded } ?: CommandState.Succeeded
            }
    }

    private fun <T> Sequence<T>.doSequentially(
        initial: MutableList<CommandState>,
        action: (T) -> Promise<CommandState>
    ): Promise<List<CommandState>> {
        return this.fold(Promise.resolve(initial)) { acc, element ->
            acc.then { list ->
                action(element).then { jobResult ->
                    //do not continue with next command if a previous was not successful
                    if (jobResult === CommandState.Failed) throw ReleaseFailure
                    list.add(jobResult)
                    list
                }
            }.unsafeCast<Promise<MutableList<CommandState>>>()
        }
    }

    private fun triggerReleaseCommands(paramObject: ParamObject): Promise<CommandState> {
        return paramObject.project.commands
            .asSequence()
            .mapWithIndex()
            .filter { it.value is ReleaseCommand }
            .doSequentially(mutableListOf()) { (index, command) ->
                createCommandPromise(paramObject, command, index)
            }.then { jobsResults ->
                jobsResults.firstOrNull { it !== CommandState.Succeeded } ?: CommandState.Succeeded
            }
    }

    private fun createCommandPromise(paramObject: ParamObject, command: Command, index: Int): Promise<CommandState> {
        val state = Pipeline.getCommandState(paramObject.project.id, index)
        return if (state === CommandState.Ready || state === CommandState.ReadyToReTrigger) {
            triggerCommand(paramObject, command, index)
        } else if (state === CommandState.RePolling) {
            rePollCommand(paramObject, command, index)
        } else {
            Promise.resolve(state)
        }
    }

    private fun triggerCommand(paramObject: ParamObject, command: Command, index: Int): Promise<CommandState> {
        val jobExecutionData = paramObject.jobExecutionDataFactory.create(paramObject.project, command)
        return triggerJob(paramObject, index, jobExecutionData)
    }

    private fun rePollCommand(
        paramObject: ParamObject,
        command: Command,
        index: Int
    ): Promise<CommandState> {
        if (command !is JenkinsCommand) {
            throw IllegalStateException("We do not know how to re-poll a non Jenkins command.\nGiven Command: $command")
        }
        val buildUrl = command.buildUrl
            ?: throw IllegalStateException("We do not know how to re-poll a Jenkins command if it does not have a specified build url.\nGiven Command: $command")

        val jobExecutionData = paramObject.jobExecutionDataFactory.create(paramObject.project, command)
        val buildNumber = buildUrl.substringAfter(jobExecutionData.jobBaseUrl).substringBefore("/").toInt()
        return paramObject.jobExecutor.rePoll(
            jobExecutionData,
            buildNumber,
            POLL_EVERY_SECOND,
            MAX_WAIT_FOR_COMPLETION
        ).finalizeJob(paramObject, jobExecutionData, index)
    }

    private fun triggerJob(
        paramObject: ParamObject,
        index: Int,
        jobExecutionData: JobExecutionData
    ): Promise<CommandState> {
        return paramObject.jobExecutor.trigger(jobExecutionData,
            { queuedItemUrl ->
                Pipeline.changeStateOfCommandAndAddBuildUrlIfSet(
                    paramObject.project,
                    index,
                    CommandState.Queueing,
                    Pipeline.STATE_QUEUEING,
                    queuedItemUrl
                )
                quietSave(paramObject)
            }, { buildNumber ->
                Pipeline.changeStateOfCommandAndAddBuildUrl(
                    paramObject.project,
                    index,
                    CommandState.InProgress,
                    Pipeline.STATE_IN_PROGRESS,
                    "${jobExecutionData.jobBaseUrl}$buildNumber/"
                )
                Promise.resolve(1)
            },
            POLL_EVERY_SECOND,
            MAX_WAIT_FOR_COMPLETION,
            verbose = false
        ).finalizeJob(paramObject, jobExecutionData, index)
    }

    private fun Promise<*>.finalizeJob(
        paramObject: ParamObject,
        jobExecutionData: JobExecutionData,
        index: Int
    ): Promise<CommandState> {
        return this.then(
            onFulfilled = { onJobEndedSuccessFully(paramObject.project, index) },
            onRejected = { t -> onJobEndedWithFailure(t, jobExecutionData, paramObject.project, index) }
        )
    }

    private fun onJobEndedSuccessFully(project: Project, index: Int): CommandState {
        Pipeline.changeStateOfCommand(project, index, CommandState.Succeeded, Pipeline.STATE_SUCCEEDED)
        return CommandState.Succeeded
    }

    private fun onJobEndedWithFailure(
        t: Throwable,
        jobExecutionData: JobExecutionData,
        project: Project,
        index: Int
    ): CommandState {
        showThrowable(Error("Job ${jobExecutionData.jobName} failed", t))
        val state = elementById<HTMLAnchorElement>(
            "${Pipeline.getCommandId(project, index)}${Pipeline.STATE_SUFFIX}"
        )
        val href = if (!state.href.endsWith(endOfConsoleUrlSuffix)) {
            state.href + endOfConsoleUrlSuffix
        } else {
            state.href
        }
        Pipeline.changeStateOfCommandAndAddBuildUrl(
            project,
            index,
            CommandState.Failed,
            Pipeline.STATE_FAILED,
            href
        )
        return CommandState.Failed
    }

    private fun quietSave(paramObject: ParamObject, verbose: Boolean = false): Promise<Unit> {
        return menu.save(paramObject.jobExecutor, verbose)
            .then { hadChanges ->
                if (!hadChanges) {
                    showWarning(
                        "Could not save changes for project ${paramObject.project.id.identifier}." +
                            "\nPlease report a bug: $GITHUB_NEW_ISSUE"
                    )
                }
            }.catch {
                console.error("save failed for ${paramObject.project}", it)
                // we ignore if a save fails at this point,
                // the next command performs a save as well and we track if the final save fails
            }
    }

    companion object {
        const val POLL_EVERY_SECOND = 5
        const val MAX_WAIT_FOR_COMPLETION = 60 * 15
    }

    private data class ParamObject(
        val releasePlan: ReleasePlan,
        val jobExecutor: JobExecutor,
        val jobExecutionDataFactory: JobExecutionDataFactory,
        val project: Project,
        private val locks: HashMap<ProjectId, Promise<*>>,
        val projectResults: HashMap<ProjectId, CommandState>
    ) {

        constructor(paramObject: ParamObject, newProjectId: ProjectId)
            : this(paramObject, paramObject.releasePlan.getProject(newProjectId))

        constructor(paramObject: ParamObject, newProject: Project)
            : this(
            paramObject.releasePlan,
            paramObject.jobExecutor,
            paramObject.jobExecutionDataFactory,
            newProject,
            paramObject.locks,
            paramObject.projectResults
        )

        fun <T> withLockForProject(act: () -> Promise<T>): Promise<T> {
            val projectId = project.id
            val lock = locks[projectId]
            return if (lock == null) {
                val promise = act()
                locks[projectId] = promise
                promise.then { result ->
                    locks.remove(projectId)
                    result
                }
            } else {
                lock.then { withLockForProject(act) }.unsafeCast<Promise<T>>()
            }
        }
    }

    private object ReleaseFailure : RuntimeException()
}
