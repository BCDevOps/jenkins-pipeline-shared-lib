def String call() {
   Date date = new Date()
   return date.format('yyyyMMddHHmmss',TimeZone.getTimeZone('PST')) as String
}
return this
