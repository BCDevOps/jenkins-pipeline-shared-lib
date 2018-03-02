import groovy.json.JsonOutput
def call(title, text, color, hookurl, channel, actions=[]) {
    def jenkinsIcon = 'https://wiki.jenkins-ci.org/download/attachments/2916393/logo.png'
    def slackURL = hookurl
    def payloadJson = [
        channel: channel,
        username: "Jenkins",
        icon_url: jenkinsIcon,
        attachments: [[
            fallback: text,
            color: color,
            author_name: env.CHANGE_AUTHOR_DISPLAY_NAME,
            fields: [
                [
                    title: title,
                    value: text,
                    short: false
                ],
                [
                    title: "Change Summary",
                    value: env.CHANGE_TITLE,
                    short: false
                ]
            ],
            actions:actions
        ]]
    ]
    def encodedReq = URLEncoder.encode(JsonOutput.toJson(payloadJson), "UTF-8")
    sh("curl -s -S -X POST " +
            "--data \'payload=${encodedReq}\' ${slackURL}")
}
