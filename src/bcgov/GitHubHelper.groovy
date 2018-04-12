package bcgov

import org.kohsuke.github.*
import org.jenkinsci.plugins.workflow.cps.CpsScript
import com.cloudbees.jenkins.GitHubRepositoryName

/*
* Reference:
*   - http://github-api.kohsuke.org/apidocs/index.html
*   - https://github.com/jenkinsci/github-plugin/blob/master/src/main/java/com/cloudbees/jenkins/GitHubRepositoryName.java
* */
class GitHubHelper {
    static GHRepository getGitHubRepository(CpsScript script){
        return getGitHubRepository(script.scm.getUserRemoteConfigs()[0].getUrl())
    }

    @NonCPS
    static GHRepository getGitHubRepository(String url){
        return GitHubRepositoryName.create(url).resolveOne()
    }

    static GHPullRequest getPullRequest(CpsScript script){
        return getGitHubRepository(script).getPullRequest(Integer.parseInt(script.env.CHANGE_ID))
    }

    static String getPullRequestLastCommitId(CpsScript script){
        return getPullRequest(script).getHead().getSha()
    }

    @NonCPS
    static boolean mergeAndClosePullRequest(String repositoryUrl, int prNumber){
        GHRepository repo=getGitHubRepository(repositoryUrl)
        GHPullRequest pullRequest = repo.getPullRequest(prNumber)
        Boolean mergeable = pullRequest.getMergeable()
        GHIssueState state = pullRequest.getState()
        boolean ret=false
        boolean doClose=true;

        if (state != GHIssueState.CLOSED) {
            GHCommitPointer head = pullRequest.getHead()
            if (pullRequest.getRepository().getFullName().equalsIgnoreCase(head.getRepository().getFullName())) {
                if (!pullRequest.isMerged()) {
                    if (mergeable != null && mergeable.booleanValue() == true) {
                        pullRequest.merge("Merged PR-${prNumber}", head.getSha(), GHPullRequest.MergeMethod.MERGE)
                    } else {
                        doClose = false
                    }
                }

                if (head.getRef() != null) {
                    GHRef headRef = repo.getRef('heads/' + head.getRef())
                    if (headRef != null) {
                        headRef.delete()
                    }
                }
            }
            if (doClose){
                pullRequest.close()
                ret = true
            }
        }else{
            ret = true
        }

        return ret
    }

    static boolean mergeAndClosePullRequest(CpsScript script) {
        return mergeAndClosePullRequest(script.scm.getUserRemoteConfigs()[0].getUrl(), Integer.parseInt(script.env.CHANGE_ID))
    }

    static GHDeploymentBuilder createDeployment(CpsScript script, String ref) {
        return getGitHubRepository(script).createDeployment(ref)
    }


    static GHDeploymentBuilder createDeployment(String url, String ref) {
        return getGitHubRepository(url).createDeployment(ref)
    }

    static def createDeploymentStatus(CpsScript script, long deploymentId, GHDeploymentState state) {
        return getGitHubRepository(script).getDeployment(deploymentId).createStatus(state)
    }

    /*
    * http://github-api.kohsuke.org/apidocs/org/kohsuke/github/GHDeploymentBuilder.html
    * */
    @NonCPS
    def createDeployment(String url, String ref, Map deploymentConfig) {
        //long deploymentId = -1
        GHRepository repository=getGitHubRepository(url)

        /*
        for (GHDeployment deployment:repository.listDeployments(null, ref, null, deploymentConfig.environment)){
            deployment.createStatus(GHDeploymentState.PENDING).create()
            return deployment.getId()
        }
        */
        GHDeploymentBuilder builder=repository.createDeployment(ref)
        builder.environment(deploymentConfig.environment)
        builder.autoMerge(false)
        builder.requiredContexts([])


        //deployment=null

        if (deploymentConfig!=null) {
            //if (deploymentConfig.environment) {
            //    builder.environment(deploymentConfig.environment)
            //}

            if (deploymentConfig.payload) {
                builder.payload(deploymentConfig.payload)
            }

            if (deploymentConfig.description) {
                builder.description(deploymentConfig.description)
            }

            if (deploymentConfig.task) {
                builder.task(deploymentConfig.task)
            }

            if (deploymentConfig.requiredContexts) {
                builder.requiredContexts(deploymentConfig.requiredContexts)
            }
        }

        /*
        long deploymentId = builder.create().getId()
        builder=null;
        return deploymentId
        */

        return builder.create().getId()
    }

    long createDeployment(CpsScript script, String ref, Map deploymentConfig) {
        script.echo "ref:${ref} - config:${deploymentConfig}"
        return createDeployment(script.scm.getUserRemoteConfigs()[0].getUrl(), ref, deploymentConfig)
    }

    @NonCPS
    static long createDeploymentStatus(String url, long deploymentId, String statusName, Map deploymentStatusConfig) {
        def ghRepo=getGitHubRepository(url)
        def ghDeploymentState=GHDeploymentState.valueOf(statusName)

        def ghDeploymentStatus=ghRepo.root.retrieve().to(ghRepo.getApiTailUrl("deployments/")  + deploymentId, GHDeployment.class).wrap(ghRepo).createStatus(ghDeploymentState)

        if (deploymentStatusConfig.description){
            ghDeploymentStatus.description(deploymentStatusConfig.description)
        }
        if (deploymentStatusConfig.targetUrl){
            ghDeploymentStatus.targetUrl(deploymentStatusConfig.targetUrl)
        }
        return ghDeploymentStatus.create().getId()
    }
    static long createDeploymentStatus(CpsScript script, long deploymentId, String statusName, Map config) {
        script.echo "deploymentId:${deploymentId} - status:${statusName} - config:${config}"
        return createDeploymentStatus(script.scm.getUserRemoteConfigs()[0].getUrl(), deploymentId, statusName, config)
    }
    @NonCPS
    void createCommitStatus(String url, String sha1, String statusName, String targetUrl, String description, String context) {
        def ghRepo=getGitHubRepository(url)
        def ghCommitState=GHCommitState.valueOf(statusName)

        ghRepo.createCommitStatus(sha1, ghCommitState, targetUrl, description, context)
    }

    void createCommitStatus(CpsScript script, String ref, String statusName, String targetUrl, String description, String context) {
        createCommitStatus(script.scm.getUserRemoteConfigs()[0].getUrl() as String, ref, statusName, targetUrl, description, context)
    }
}