
/*
* References:
* - https://zwischenzugs.com/2017/04/23/things-i-wish-i-knew-before-using-jenkins-pipelines/
* - https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
* - https://jenkins.io/doc/pipeline/examples/
*/

import hudson.model.Result;
import jenkins.model.CauseOfInterruption.UserInterruption;
import org.kohsuke.github.*
import bcgov.OpenShiftHelper
import bcgov.GitHubHelper


def call(body) {
    def context= [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = context
    body()


    properties([
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10')),
            durabilityHint('MAX_SURVIVABILITY'),
            parameters([string(defaultValue: '', description: '', name: 'run_stages')])
    ])


    stage('Prepare') {
        abortAllPreviousBuildInProgress(currentBuild)
        echo "BRANCH_NAME=${env.BRANCH_NAME}\nCHANGE_ID=${env.CHANGE_ID}\nCHANGE_TARGET=${env.CHANGE_TARGET}\nBUILD_URL=${env.BUILD_URL}"
        //def pullRequest=GitHubHelper.getPullRequest(this)
        //echo "Pull-Request: ${pullRequest}"
        //echo "Pull-Request: head.ref: ${pullRequest.getHead().getRef()}"
    }

    stage('Build') {
        node('build') {
            checkout scm
            new OpenShiftHelper().build(this, context)
            if ("master".equalsIgnoreCase(env.CHANGE_TARGET)) {
                new OpenShiftHelper().prepareForCD(this, context)
            }
        }
    }
    for(String envKeyName: context.env.keySet() as String[]){
        String stageDeployName=envKeyName.toUpperCase()

        if ("DEV".equalsIgnoreCase(stageDeployName) || "master".equalsIgnoreCase(env.CHANGE_TARGET)) {
            stage("Readiness - ${stageDeployName}") {
                node('build') {
                    new OpenShiftHelper().waitUntilEnvironmentIsReady(this, context, envKeyName)
                }
            }
        }

        if (!"DEV".equalsIgnoreCase(stageDeployName) && "master".equalsIgnoreCase(env.CHANGE_TARGET)){
            stage("Approve - ${stageDeployName}") {
                def inputResponse = input(id: "deploy_${stageDeployName.toLowerCase()}", message: "Deploy to ${stageDeployName}?", ok: 'Approve', submitterParameter: 'approved_by')
                //echo "inputResponse:${inputResponse}"
                GitHubHelper.getPullRequest(this).comment("User '${inputResponse}' has approved deployment to '${stageDeployName}'")
            }
        }

        if ("DEV".equalsIgnoreCase(stageDeployName) || "master".equalsIgnoreCase(env.CHANGE_TARGET)){
            stage("Deploy - ${stageDeployName}") {
                node('build') {
                    new OpenShiftHelper().deploy(this, context, envKeyName)
                }
            }
        }
    }

    stage('Cleanup') {
        def inputResponse=input(id: 'close_pr', message: "Ready to Accept/Merge, and Close pull-request #${env.CHANGE_ID}?", ok: 'Yes', submitter: 'authenticated', submitterParameter: 'approver')
        echo "inputResponse:${inputResponse}"

        new OpenShiftHelper().cleanup(this, context)
        GitHubHelper.mergeAndClosePullRequest(this)
    }
}
