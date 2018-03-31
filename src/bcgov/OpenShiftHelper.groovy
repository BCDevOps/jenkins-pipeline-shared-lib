package bcgov;

import org.jenkinsci.plugins.workflow.cps.CpsScript;
import com.openshift.jenkins.plugins.OpenShiftDSL;

class OpenShiftHelper {
    int logLevel=0
    static List PROTECTED_TYPES = ['Secret', 'ConfigMap', 'PersistentVolumeClaim']
    static String ANNOTATION_ALLOW_CREATE='template.openshift.io.bcgov/create'
    static String ANNOTATION_ALLOW_UPDATE='template.openshift.io.bcgov/update'

    private void loadMetadata(CpsScript script, Map metadata) {
        metadata.commitId = script.sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
        metadata.isPullRequest=(script.env.CHANGE_ID != null && script.env.CHANGE_ID.trim().length()>0)
        metadata.gitRepoUrl = script.scm.getUserRemoteConfigs()[0].getUrl()

        metadata.buildBranchName = script.env.BRANCH_NAME;
        metadata.buildEnvName = 'bld'
        metadata.buildNamePrefix = "${metadata.appName}"


        if (metadata.isPullRequest){
            metadata.pullRequestNumber=script.env.CHANGE_ID
            metadata.gitBranchRemoteRef="refs/pull/${metadata.pullRequestNumber}/head";
            metadata.buildEnvName="pr-${metadata.pullRequestNumber}"
        }

        metadata.buildNameSuffix = "-${metadata.buildEnvName}"
    }


    private boolean allowCreateOrUpdate(Map newModel, Map currentModel) {

        if(PROTECTED_TYPES.contains(newModel.kind)){
            if (
            (currentModel==null && Boolean.parseBoolean(newModel.metadata?.annotations[ANNOTATION_ALLOW_CREATE]?:'false')==true) ||
                    (currentModel!=null && Boolean.parseBoolean(newModel.metadata?.annotations[ANNOTATION_ALLOW_UPDATE]?:'false')==true)
            ){
                return true
            }
        }else {
            return true
        }
        return false
    }

    @NonCPS
    private List getTemplateParameters(String parameters) {
        List ret=[]
        boolean isTitleLine=true
        for (String line:parameters.tokenize('\n')){
            if (!isTitleLine){
                ret.add(line.tokenize()[0])
            }
            isTitleLine=false;
        }
        return ret;
    }

    @NonCPS
    private def processStringTemplate(String template, Map bindings) {
        def engine = new groovy.text.GStringTemplateEngine()
        return engine.createTemplate(template).make(bindings).toString()
    }

    @NonCPS
    private List createProcessTemplateParameters(Map params, Map bindings) {
        def engine = new groovy.text.GStringTemplateEngine()
        def ret=[]
        for (String paramName:params.keySet()) {
            ret.add('-p')
            ret.add(paramName+'='+engine.createTemplate(params[paramName]).make(bindings).toString())
        }
        return ret
    }

    private Map loadObjectsFromTemplate(OpenShiftDSL openshift, List templates, Map context, String purpose){
        def models = [:]
        if (templates !=null && templates.size() > 0) {
            for (Map template : templates) {
                List parameters=getTemplateParameters(openshift.raw('process', '-f', template.file, '--parameters').out)
                template.params = template.params?:[:]

                for (String paramName:parameters){
                    if ('build'.equals(purpose)) {
                        if ('NAME_SUFFIX'.equals(paramName)) {
                            template.params[paramName] = '${buildNameSuffix}'
                        } else if ('SOURCE_REPOSITORY_URL'.equals(paramName)) {
                            template.params[paramName] = '${gitRepoUrl}'
                        } else if ('ENV_NAME'.equals(paramName)) {
                            template.params[paramName] = '${buildEnvName}'
                        }
                    }else if ('deployment'.equals(purpose)) {
                        if ('NAME_SUFFIX'.equals(paramName)) {
                            template.params[paramName] = '${deploy.dcSuffix}'
                        } else if ('SOURCE_REPOSITORY_URL'.equals(paramName)) {
                            template.params[paramName] = '${gitRepoUrl}'
                        } else if ('ENV_NAME'.equals(paramName)) {
                            template.params[paramName] = '${deploy.envName}'
                        } else if ('BUILD_ENV_NAME'.equals(paramName)) {
                            template.params[paramName] = '${buildEnvName}'
                        }
                    }
                }
                List params=createProcessTemplateParameters(template.params, context)
                String firstParam=template?.template
                if (template.file){
                    firstParam='-f'
                    params.add(0, template.file)
                }
                for (Map model in openshift.process(firstParam, params)){
                    models[key(model)] = model
                }
            }
        }
        return models
    }

    private Map loadObjectsByLabel(OpenShiftDSL openshift, Map labels){
        def models = [:]
        def selector=openshift.selector('is,bc,secret,configmap,dc,svc,route', labels)

        if (selector.count()>0) {
            for (Map model : selector.objects(exportable: true)) {
                models[key(model)] = model
            }
        }
        return models
    }

    private Map loadBuildConfigStatus(OpenShiftDSL openshift, Map labels){
        Map buildOutput = [:]
        def selector=openshift.selector('bc', labels)

        if (selector.count()>0) {
            for (Map bc : selector.objects()) {
                String buildName = "Build/${bc.metadata.name}-${bc.status.lastVersion}"
                Map build = openshift.selector(buildName).object()
                buildOutput[buildName] = [
                        'kind': build.kind,
                        'metadata': ['name':build.metadata.name],
                        'output': [
                                'to': [
                                        'kind': build.spec.output.to.kind,
                                        'name': build.spec.output.to.name
                                ]
                        ],
                        'status': ['phase': build.status.phase]
                ]

                if (isBuildSuccesful(build)) {
                    buildOutput["${build.spec.output.to.kind}/${build.spec.output.to.name}"] = [
                            'kind': build.spec.output.to.kind,
                            'metadata': ['name':build.spec.output.to.name],
                            'imageDigest': build.status.output.to.imageDigest,
                            'outputDockerImageReference': build.status.outputDockerImageReference
                    ]
                }

                buildOutput["${key(bc)}"] = [
                        'kind': bc.kind,
                        'metadata': ['name':bc.metadata.name],
                        'status': ['lastVersion':bc.status.lastVersion, 'lastBuildName':buildName]
                ]
            }
        }
        return buildOutput
    }

    @NonCPS
    private String key(Map model){
        return "${model.kind}/${model.metadata.name}"
    }

    private void waitForDeploymentsToComplete(CpsScript script, OpenShiftDSL openshift, Map labels){
        script.echo "Waiting for deployments with labels ${labels}"

        Map rcLabels=[:]
        openshift.selector('dc', labels).withEach { it ->
            def dc=it.object()
            rcLabels['openshift.io/deployment-config.name']= dc.metadata.name
        }

        boolean doCheck=true
        while(doCheck) {
            openshift.selector('rc', rcLabels).watch {
                boolean allDone = true
                it.withEach { item ->
                    def object = item.object()
                    script.echo "${key(object)} - ${getReplicationControllerStatus(object)}"
                    if (!isReplicationControllerComplete(object)) {
                        allDone = false
                    }
                }
                return allDone
            }

            script.sleep 5
            doCheck=false
            for (Map build:openshift.selector('rc', rcLabels).objects()){
                if (!isReplicationControllerComplete(build)) {
                    doCheck=true
                    break
                }
            }
        }

        doCheck=true
        while(doCheck) {
            openshift.selector('dc', labels).watch {
                boolean allDone = true
                it.withEach { item ->
                    def dc = item.object()
                    script.echo "${key(dc)} - desired:${dc?.status?.replicas}  ready:${dc?.status?.readyReplicas} available:${dc?.status?.availableReplicas}"
                    if (!(dc?.status?.replicas == dc?.status?.readyReplicas &&  dc?.status?.replicas == dc?.status?.availableReplicas)) {
                        allDone = false
                    }
                }
                return allDone
            }
            script.sleep 5
            doCheck=false
            for (Map dc : openshift.selector('dc', labels).objects()){
                if (!(dc?.status?.replicas == dc?.status?.readyReplicas &&  dc?.status?.replicas == dc?.status?.availableReplicas)) {
                    doCheck=true
                    break
                }
            }
        }
    }

    private void waitForBuildsToComplete(CpsScript script, OpenShiftDSL openshift, Map labels){
        //openshift.verbose(true)
        script.echo "Waiting for builds with labels ${labels}"
        boolean doCheck=true
        while(doCheck) {
            openshift.selector('builds', labels).watch {
                boolean allDone = true
                it.withEach { item ->
                    def object = item.object()
                    script.echo "${key(object)} - ${object.status.phase}"
                    if (!isBuildComplete(object)) {
                        allDone = false
                    }
                }
                return allDone
            }
            script.sleep 5
            doCheck=false
            for (Map build:openshift.selector('builds', labels).objects()){
                if (!isBuildComplete(build)) {
                    doCheck=true
                    break
                }
            }
        }
        //openshift.verbose(false)
    }

    private void checkProjectsAccess(CpsScript script, OpenShiftDSL openshift, Map context){
        String currentUser= openshift.raw('whoami').out.tokenize()[0]
        script.waitUntil {
            boolean isReady = true
            List projects=[]
            List accessibleProjects=openshift.raw('projects', '-q').out.tokenize()
            for(Map env: context.env.values()){
                projects.add(env.project)
            }

            script.echo "Accessible Projects '${accessibleProjects}'"

            for(String projectName: projects.unique()){
                if (!accessibleProjects.contains(projectName)){
                    isReady=false
                    script.echo "Cannot access project '${projectName}'. Please run:"
                    script.echo "  oc policy add-role-to-user edit ${currentUser} -n ${projectName}"
                }
            }

            if (!isReady) {
                script.input "Retry Access Check?"
            }

            return isReady
        }
    }

    def build(CpsScript script, Map context) {
        OpenShiftDSL openshift=script.openshift;

        def stashIncludes=[]
        for ( List templates : context.templates.values()){
            for ( Map template : templates){
                if (template.file){
                    stashIncludes.add(template.file)
                }
            }
        }

        script.echo "BRANCH_NAME=${script.env.BRANCH_NAME}\nCHANGE_ID=${script.env.CHANGE_ID}\nCHANGE_TARGET=${script.env.CHANGE_TARGET}\nBUILD_URL=${script.env.BUILD_URL}"

        loadMetadata(script, context)

        context['ENV_KEY_NAME'] = 'build'
        script.stash(name: 'openshift', includes:stashIncludes.join(','))
        openshift.withCluster() {
            openshift.withProject(openshift.project()) {
                checkProjectsAccess(script, openshift, context)

                script.echo "Connected to project '${openshift.project()}' as user '${openshift.raw('whoami').out.tokenize()[0]}'"
                Map labels=['app-name': context.name, 'env-name': context.buildEnvName]
                def newObjects = loadObjectsFromTemplate(openshift, context.templates.build, context, 'build')
                def currentObjects = loadObjectsByLabel(openshift, labels)

                for (Map m : newObjects.values()){
                    if ('BuildConfig'.equalsIgnoreCase(m.kind)){
                        String commitId = context.commitId
                        String contextDir=null

                        if (m.spec && m.spec.source && m.spec.source.contextDir){
                            contextDir=m.spec.source.contextDir
                        }

                        if (contextDir!=null && contextDir.startsWith('/') && !contextDir.equalsIgnoreCase('/')){
                            contextDir=contextDir.substring(1)
                        }

                        if (contextDir!=null){
                            commitId=script.sh(returnStdout: true, script: "git rev-list -1 HEAD -- '${contextDir}'").trim()
                        }
                        if (!m.metadata.annotations) m.metadata.annotations=[:]
                        if (m.spec.source.git.ref) m.metadata.annotations['source/spec.source.git.ref']=m.spec.source.git.ref

                        m.metadata.annotations['spec.source.git.ref']=commitId
                        m.spec.source.git.ref=commitId
                        m.spec.runPolicy = 'SerialLatestOnly'
                        script.echo "${key(m)} - ${contextDir?:'/'} @ ${m.spec.source.git.ref}"
                    }
                }

                def initialBuildConfigState=loadBuildConfigStatus(openshift, labels)

                applyBuildConfig(script, openshift, context.name, context.buildEnvName, newObjects, currentObjects);
                script.echo "Waiting for builds to complete"

                waitForBuildsToComplete(script, openshift, labels)
                def startedNewBuilds=false

                def postBuildConfigState=loadBuildConfigStatus(openshift, labels)
                for (Map item: initialBuildConfigState.values()){
                    //script.echo "${item}"
                    if ('BuildConfig'.equalsIgnoreCase(item.kind)){
                        Map newItem=postBuildConfigState[key(item)]
                        Map build=initialBuildConfigState["Build/${item.metadata.name}-${item.status.lastVersion}"]
                        if (item.status.lastVersion == newItem.status.lastVersion && !isBuildSuccesful(build)){
                            openshift.selector(key(item)).startBuild()
                            startedNewBuilds=true
                        }
                    }
                }

                if (startedNewBuilds) {
                    waitForBuildsToComplete(script, openshift, labels)
                }

                def buildOutput=loadBuildConfigStatus(openshift, labels)
                boolean allBuildSuccessful=true
                for (Map item: buildOutput.values()){
                    if ('BuildConfig'.equalsIgnoreCase(item.kind)){
                        Map build=buildOutput["Build/${item.metadata.name}-${item.status.lastVersion}"]
                        if (!isBuildSuccesful(build)){
                            allBuildSuccessful=false
                            break;
                        }
                    }
                }
                if (!allBuildSuccessful){
                    script.error('Sorry, not all builds have been successful! :`(')
                }

                openshift.selector( 'is', labels).withEach {
                    def iso=it.object()

                    buildOutput["${key(iso)}"] = [
                            'kind': iso.kind,
                            'metadata': ['name':iso.metadata.name, 'namespace':iso.metadata.namespace],
                            'labels':iso.metadata.labels
                    ]
                    String baseName=getImageStreamBaseName(iso)
                    buildOutput["BaseImageStream/${baseName}"]=['ImageStream':key(iso)]
                }

                context['build'] = ['status':buildOutput, 'projectName':"${openshift.project()}"]


            }
        }
    }

    private def applyBuildConfig(CpsScript script, OpenShiftDSL openshift, String appName, String envName, Map models, Map currentModels) {
        def bcSelector = ['app-name': appName, 'env-name': envName]

        if (logLevel >= 4 ) script.echo "openShiftApplyBuildConfig:openshift1:${openshift.dump()}"


        script.echo "Processing ${models.size()} objects for '${appName}' for '${envName}'"
        def creations=[]
        def updates=[]
        def patches=[]

        for (Object o : models.values()) {
            if (logLevel >= 4 ) script.echo "Processing '${o.kind}/${o.metadata.name}' (before apply)"
            if (o.metadata.labels==null) o.metadata.labels =[:]
            o.metadata.labels["app"] = "${appName}-${envName}"
            o.metadata.labels["app-name"] = "${appName}"
            o.metadata.labels["env-name"] = "${envName}"

            def sel=openshift.selector("${o.kind}/${o.metadata.name}")
            if (sel.count()==0){
                //script.echo "Creating '${o.kind}/${o.metadata.name}'"
                creations.add(o)
            }else{
                if (!'ImageStream'.equalsIgnoreCase("${o.kind}")){
                    script.echo "Skipping '${key(o)}'"
                    //updates.add(o)
                    patches.add(o)
                }else{
                    //script.echo "Skipping '${o.kind}/${o.metadata.name}' (Already Exists)"
                    def newObject=o
                    if (newObject.spec && newObject.spec.tags){
                        newObject.spec.remove('tags')
                    }
                    //script.echo "Modified '${o.kind}/${o.metadata.name}' = ${newObject}"
                    updates.add(newObject)
                }
            }

        }

        if (creations.size()>0){
            script.echo "Creating ${creations.size()} objects"
            openshift.apply(creations);
        }
        if (updates.size()>0){
            script.echo "Updating ${updates.size()} objects"
            openshift.apply(updates);
        }

    }

    private String getReplicationControllerStatus(rc) {
        return rc.metadata.annotations['openshift.io/deployment.phase']
    }

    private def isReplicationControllerComplete(rc) {
        String phase=getReplicationControllerStatus(rc)
        return ("Complete".equalsIgnoreCase(phase) || "Cancelled".equalsIgnoreCase(phase) || "Failed".equalsIgnoreCase(phase) || "Error".equalsIgnoreCase(phase))
    }

    private def isBuildComplete(build) {
        return ("Complete".equalsIgnoreCase(build.status.phase) || "Cancelled".equalsIgnoreCase(build.status.phase) || "Failed".equalsIgnoreCase(build.status.phase) || "Error".equalsIgnoreCase(build.status.phase))
    }

    private def isBuildSuccesful(build) {
        return "Complete".equalsIgnoreCase(build.status.phase)
    }

    @NonCPS
    private def getImageStreamBaseName(res) {
        String baseName=res.metadata.name
        if (res.metadata && res.metadata.labels && res.metadata.labels['base-name']){
            baseName=res.metadata.labels['base-name']
        }
        return baseName
    }

    void waitUntilEnvironmentIsReady(CpsScript script, Map context, String envKeyName){
        OpenShiftDSL openshift=script.openshift
        script.waitUntil {
            try {
                Map deployCfg = createDeployContext(script, context, envKeyName)
                return true
            } catch (ex) {
                script.input "Retry Environment Readiness Check?"
                return false
            }
        }
    }

    private Map createDeployContext(CpsScript script, Map context, String envKeyName) {
        String envName = envKeyName.toLowerCase()
        if ("DEV".equalsIgnoreCase(envKeyName)) {
            envName = "dev-pr-${script.env.CHANGE_ID}"
        }
        Map deployCfg = [
                'envName':envName,
                'projectName':context.env[envKeyName].project,
                'envKeyName':envKeyName
        ]

        if (!deployCfg.dcPrefix) deployCfg.dcPrefix = context.name
        if (!deployCfg.dcSuffix) deployCfg.dcSuffix = "-${deployCfg.envName}"

        deployCfg['labels']=['app-name':context.name, 'env-name':envName]

        return deployCfg
    }
    void deploy(CpsScript script, Map context, String envKeyName) {
        OpenShiftDSL openshift=script.openshift
        Map deployCfg = createDeployContext(script, context, envKeyName)
        context['deploy'] = deployCfg
        script.echo "Deploying to ${envKeyName.toUpperCase()} as ${deployCfg.envName}"

        def ghDeploymentId = new GitHubHelper().createDeployment(script, GitHubHelper.getPullRequest(script).getHead().getSha(), ['environment':"${envKeyName.toUpperCase()}"])
        //try {
            //GitHubHelper.getPullRequest(script).comment("Build in progress")
            //GitHubHelper.getPullRequest(script).comment("Deploying to DEV")

            script.unstash(name: 'openshift')

            context['DEPLOY_ENV_NAME'] = envKeyName

            script.echo "Deploying '${context.name}' to '${context.deploy.envName}'"
            openshift.withCluster() {
                script.echo "Connected to project '${openshift.project()}' as user '${openshift.raw('whoami').out}'"

                //openshift.withCredentials( 'jenkins-deployer-dev.token' ) {
                openshift.withProject(deployCfg.projectName) {
                    script.echo "Connected to project '${openshift.project()}' as user '${openshift.raw('whoami').out}'"
                    //script.echo "DeployModels:${models}"
                    applyDeploymentConfig(script, openshift, context)


                } // end openshift.withProject()
                //} // end openshift.withCredentials()
            } // end openshift.withCluster()
            context.remove('deploy')
            new GitHubHelper().createDeploymentStatus(script, ghDeploymentId, 'SUCCESS', [:])
        //}catch (all) {
        //    new GitHubHelper().createDeploymentStatus(script, ghDeploymentId, 'ERROR', [:])
        //    throw new Exception(all)
        //}
    } // end 'deploy' method

    private def updateContainerImages(CpsScript script, OpenShiftDSL openshift, containers, triggers) {
        for ( c in containers ) {
            for ( t in triggers) {
                if ('ImageChange'.equalsIgnoreCase(t['type'])){
                    for ( cn in t.imageChangeParams.containerNames){
                        if (cn.equalsIgnoreCase(c.name)){
                            if (logLevel >= 4 ) script.echo "${t.imageChangeParams.from}"
                            def dockerImageReference = ' '
                            def selector=openshift.selector("istag/${t.imageChangeParams.from.name}")

                            if (t.imageChangeParams.from['namespace']!=null && t.imageChangeParams.from['namespace'].length()>0){
                                openshift.withProject(t.imageChangeParams.from['namespace']) {
                                    selector=openshift.selector("istag/${t.imageChangeParams.from.name}");
                                    if (selector.count() == 1 ){
                                        dockerImageReference=selector.object().image.dockerImageReference
                                    }
                                }
                            }else{
                                selector=openshift.selector("istag/${t.imageChangeParams.from.name}");
                                if (selector.count() == 1 ){
                                    dockerImageReference=selector.object().image.dockerImageReference
                                }
                            }

                            if (logLevel >= 4 ) script.echo "ImageReference is '${dockerImageReference}'"
                            c.image = "${dockerImageReference}"
                        }
                    }
                }
            }
        }
    }

    private def applyDeploymentConfig(CpsScript script, OpenShiftDSL openshift, Map context) {
        Map deployCtx = context.deploy
        def labels=deployCtx.labels

        Map initDeploymemtConfigStatus=loadDeploymentConfigStatus(openshift, labels)
        Map models = loadObjectsFromTemplate(openshift, context.templates.deployment, context,'deployment')

        if (initDeploymemtConfigStatus.size()>0){
            for (Map dc: initDeploymemtConfigStatus.values()) {
                if ('DeploymentConfig'.equalsIgnoreCase(dc.kind)) {
                    Map newDc=models["${key(dc)}"]
                    if (newDc!=null) {
                        for (Map c : dc.spec.template.spec.containers) {
                            String dcName = c.name
                            for (Map newC : newDc.spec.template.spec.containers) {
                                if (dcName.equalsIgnoreCase(newC.name)) {
                                    newC.image = c.image
                                    script.echo "Updating '${key(dc)}' containers['${dcName}'].image=${c.image}"
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        List upserts=[]
        for (Map m : models.values()) {
            if ('ImageStream'.equalsIgnoreCase(m.kind)){
                upserts.add(m)
            }
        }
        script.echo "Applying ImageStream"
        openshift.apply(upserts)
        for (Map m : upserts) {
            String sourceImageStreamKey=context.build.status["BaseImageStream/${getImageStreamBaseName(m)}"]['ImageStream']
            Map sourceImageStream = context.build.status[sourceImageStreamKey]
            String sourceImageStreamRef="${sourceImageStream.metadata.namespace}/${sourceImageStream.metadata.name}:latest"
            String targetImageStreamRef="${m.metadata.name}:${labels['env-name']}"

            script.echo "Tagging '${sourceImageStreamRef}' as '${targetImageStreamRef}'"
            openshift.tag(sourceImageStreamRef, targetImageStreamRef)
        }
        script.echo "Applying Configurations"
        upserts.clear()
        for (Map m : models.values()) {
            Map current = initDeploymemtConfigStatus[key(m)]
            if(allowCreateOrUpdate(m, current)){
                upserts.add(m)
            }
        }
        openshift.apply(upserts).label(['app':"${labels['app-name']}-${labels['env-name']}", 'app-name':labels['app-name'], 'env-name':labels['env-name']], "--overwrite")
        waitForDeploymentsToComplete(script, openshift, labels)
    }

    private Map loadDeploymentConfigStatus(OpenShiftDSL openshift, Map labels){
        Map buildOutput = [:]
        def selector=openshift.selector('dc', labels)

        if (selector.count()>0) {
            for (Map dc : selector.objects()) {
                String rcName = "ReplicationController/${dc.metadata.name}-${dc.status.latestVersion}"
                def rcSelector = openshift.selector(rcName)
                if (rcSelector.count()>0) {
                    Map rc = rcSelector.object()
                    buildOutput[rcName] = [
                            'kind'    : rc.kind,
                            'metadata': ['name': rc.metadata.name],
                            'status'  : rc.status,
                            'phase'   : rc.metadata.annotations['openshift.io/deployment.phase']
                    ]
                }
                List containers=[]
                if (dc?.spec?.template?.spec?.containers != null){
                    for (Map c : dc.spec.template.spec.containers){
                        containers.add(['name':c.name, 'image':c.image])
                    }
                }
                buildOutput["${key(dc)}"] = [
                        'kind'    : dc.kind,
                        'metadata': ['name': dc.metadata.name],
                        'status'  : ['latestVersion': dc.status.latestVersion, 'latestReplicationControllerName': rcName],
                        'spec':[
                                'template':[
                                        'spec':[
                                                'containers':dc?.spec?.template?.spec?.containers
                                        ]
                                ]
                        ]
                ]
            }
        }
        return buildOutput
    }
} // end class
