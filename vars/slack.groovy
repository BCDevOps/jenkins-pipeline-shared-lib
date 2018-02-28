import groovy.json.JsonOutput
def notify(title, text, color, hookurl, channel) {
    def jenkinsIcon = 'https://wiki.jenkins-ci.org/download/attachments/2916393/logo.png'
    def slackURL = hookurl
    def payloadJson = [
        channel: channel,
        username: "Jenkins",
        icon_url: jenkinsIcon,
        attachments: [[
            fallback: text,
            color: color,
            fields: [
                [
                    title: title,
                    value: text,
                    short: false
                ]
            ]
        ]]
    ]
    def encodedReq = URLEncoder.encode(JsonOutput.toJson(payloadJson), "UTF-8")
    sh("curl -s -S -X POST " +
            "--data \'payload=${encodedReq}\' ${slackURL}")
}
return this
