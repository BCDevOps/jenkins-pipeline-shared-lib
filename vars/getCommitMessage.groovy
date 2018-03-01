def String call() {
  def message = ""
  def changeLogSets = currentBuild.changeSets
  if(changeLogSets.size()>0 && changeLogSets.items.size() > 0){
    message = changeLogSets[0].items[0].msg;
  }
  return message
}

