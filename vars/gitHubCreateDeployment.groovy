import bcgov.GitHubHelper

def call(script, payload) {
  def commitId = sh(returnStdout: true, script: 'git rev-parse HEAD')
  String gitUrl = script.scm.getUserRemoteConfigs()[0].getUrl()
  
  return GitHubHelper.createDeployment(gitUrl, commitId, payload)
}
