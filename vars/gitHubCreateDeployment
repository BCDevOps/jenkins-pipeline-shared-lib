import bcgov.GitHubHelper

def call(payload) {
  def commitId = sh(returnStdout: true, script: 'git rev-parse HEAD')
  return GitHubHelper.createDeployment(this, commitId, payload)
}
