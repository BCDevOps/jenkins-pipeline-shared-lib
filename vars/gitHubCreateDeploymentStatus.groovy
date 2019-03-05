import bcgov.GitHubHelper
import org.kohsuke.github.*
import com.cloudbees.jenkins.GitHubRepositoryName


def call(script, deploymentId, deploymentState, options = null) {
  def commitId = sh(returnStdout: true, script: 'git rev-parse HEAD')
  String gitUrl = script.scm.getUserRemoteConfigs()[0].getUrl()
  GHRepository repository = GitHubRepositoryName.create(gitUrl).resolveOne()

  def builder=repository.root.retrieve().to(repository.getApiTailUrl("deployments/")  + deploymentId, GHDeployment.class).wrap(repository).createStatus(GHDeploymentState.valueOf(deploymentState))
  if (options!=null){
    if (options.description){
        builder.description(options.description)
    }
    if (options.targetUrl){
        builder.targetUrl(options.targetUrl)
    }
  }

  return builder.create().getId()
}
