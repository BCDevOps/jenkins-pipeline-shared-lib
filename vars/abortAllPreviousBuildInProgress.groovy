
@NonCPS
private void abortBuild(build){
    boolean aborted=false;
    if (build instanceof org.jenkinsci.plugins.workflow.job.WorkflowRun){
        for (org.jenkinsci.plugins.workflow.support.steps.input.InputAction inputAction:build.getActions(org.jenkinsci.plugins.workflow.support.steps.input.InputAction.class)){
            for (org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution inputStep:inputAction.getExecutions()){
                if (!inputStep.isSettled()){
                    inputStep.doAbort()
                    while (build.isInProgress()){
                        Thread.sleep(1000) //milliseconds
                    }
                }
            }
        }
    }

    if (build.isInProgress()){
        build.doKill()
    }

}

def call(currentBuild) {
    while(currentBuild.rawBuild.getPreviousBuildInProgress() != null) {
        abortBuild(currentBuild.rawBuild.getPreviousBuildInProgress())
    }
}
