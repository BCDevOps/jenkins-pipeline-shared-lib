import bcgov.GitHubHelper

def call(script, payload) {
  def commitId = sh(returnStdout: true, script: 'git rev-parse HEAD')
  return GitHubHelper.createDeployment(script, commitId, payload)
}
