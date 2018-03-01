def String call() {
  def author = "Unknown"
  def changeLogSets = currentBuild.changeSets
  if(changeLogSets.size()>0 && changeLogSets.items.size() > 0){
    author = changeLogSets[0].items[0].author.fullName;
  }
  return author
}

