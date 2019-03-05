import bcgov.GitHubHelper
import org.kohsuke.github.*
import com.cloudbees.jenkins.GitHubRepositoryName


def call(script, environment) {
  def commitId = sh(returnStdout: true, script: 'git rev-parse HEAD')
  String gitUrl = script.scm.getUserRemoteConfigs()[0].getUrl()
  GHRepository repository = GitHubRepositoryName.create(gitUrl.replaceAll("[\n\r]", "").trim()).resolveOne()
  GHDeploymentBuilder builder = repository.createDeployment(commitId)
  builder.environment(environment)
  builder.autoMerge(false)
  builder.requiredContexts([])
  
  return builder.create().getId()
}
