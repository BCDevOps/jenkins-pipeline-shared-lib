import groovy.json.JsonOutput
def notify(title, text, color, icon, url, channel) {
    def slackURL = url
    def payloadJson = [
        channel: channel,
        username: "Jenkins",
        icon_url: icon,
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
