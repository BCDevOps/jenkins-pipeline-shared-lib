package bcgov;

import org.jenkinsci.plugins.workflow.cps.CpsScript;
import com.openshift.jenkins.plugins.OpenShiftDSL;
import org.kohsuke.github.GHRepository

class OpenShiftHelper {
    int logLevel=0
    static List PROTECTED_TYPES = ['Secret', 'ConfigMap', 'PersistentVolumeClaim']
    static String ANNOTATION_AS_COPY_OF ='as-copy-of'
    static String ANNOTATION_ROUTE_TLS_SECRET_NAME='template.openshift.io.bcgov/tls-secret-name'
    static String ANNOTATION_ALLOW_CREATE='template.openshift.io.bcgov/create'
    static String ANNOTATION_ALLOW_UPDATE='template.openshift.io.bcgov/update'

    @NonCPS
    private String getLastSha1InPath(String gitURL, String head, String path) {
        //if (path==null || path.length() == 0 ) return head
        GHRepository repository=GitHubHelper.getGitHubRepository(gitURL)
        return repository.queryCommits().pageSize(1).from(head).path(path).list().iterator().next().getSHA1();
    }
    
    private void loadMetadata(CpsScript script, Map metadata) {
        metadata.commitId = script.sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
        metadata.isPullRequest=(script.env.CHANGE_ID != null && script.env.CHANGE_ID.trim().length()>0)
        metadata.gitRepoUrl = script.scm.getUserRemoteConfigs()[0].getUrl()

        metadata.buildBranchName = script.env.BRANCH_NAME;
        metadata.buildEnvName = 'bld'
        metadata.buildNamePrefix = "${metadata.appName}"
        metadata.isPullRequestFromFork = false

        if (metadata.isPullRequest){
            loadPullRequestMetadata(metadata, GitHubHelper.getPullRequest(script))
            metadata.pullRequestNumber=script.env.CHANGE_ID
            metadata.gitBranchRemoteRef = script.sh(returnStdout: true, script: "git ls-remote origin 'refs/pull/${script.env.CHANGE_ID}/*' | grep '${metadata.commitId}' | cut -f2").trim()
            metadata.buildEnvName="pr-${metadata.pullRequestNumber}"
        }

        metadata.buildNameSuffix = "-${metadata.buildEnvName}"
    }

    private boolean allowCreate(Map newModel) {
        return !PROTECTED_TYPES.contains(newModel.kind) || Boolean.parseBoolean((newModel.metadata?.annotations?:[:])[ANNOTATION_ALLOW_CREATE]?:'false')==true
    }

    private boolean allowUpdate(Map newModel) {
        return !PROTECTED_TYPES.contains(newModel.kind) || Boolean.parseBoolean((newModel.metadata?.annotations?:[:])[ANNOTATION_ALLOW_UPDATE]?:'false')==true
    }

    private boolean allowCreateOrUpdate(Map newModel, Map currentModel) {
        return (currentModel==null && allowCreate(newModel)) || (currentModel!=null  && allowUpdate(newModel))
    }
    @NonCPS
    private static void loadPullRequestMetadata(Map metadata, org.kohsuke.github.GHPullRequest pullRequest) {
        metadata.isPullRequestFromFork = !pullRequest.getRepository().getFullName().equalsIgnoreCase(pullRequest.getHead().getRepository().getFullName())
    }
    @NonCPS
    private static String toJsonString(Object object) {
        return groovy.json.JsonOutput.toJson(object)
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
    private static String stackTraceAsString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString()
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
            for (Map model : selector.objects(exportable:false)) {
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
                Map build=null

                if (openshift.selector(buildName).exists()){
                    build = openshift.selector(buildName).object()
                }

                if (build!=null) {
                    buildOutput[buildName] = [
                            'kind'    : build.kind,
                            'metadata': ['name': build.metadata.name],
                            'spec'    : ['revision': build.spec.revision],
                            'output'  : [
                                    'to': [
                                            'kind': build.spec.output.to.kind,
                                            'name': build.spec.output.to.name
                                    ]
                            ],
                            'status'  : ['phase': build.status.phase]
                    ]

                    if (isBuildSuccesful(build)) {
                        buildOutput["${build.spec.output.to.kind}/${build.spec.output.to.name}"] = [
                                'kind'                      : build.spec.output.to.kind,
                                'metadata'                  : ['name': build.spec.output.to.name],
                                'imageDigest'               : build.status.output.to.imageDigest,
                                'outputDockerImageReference': build.status.outputDockerImageReference
                        ]
                    }
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
        int failures=0

        //wait for replication controllers to finish
        while(doCheck) {
            try {
                script.timeout(5) {
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
                }
            }catch (ex){
                failures++
                script.echo "${stackTraceAsString(ex)}"
                //after 10 failures, give up
                if (failures > 10){
                    throw ex
                }
                continue
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

        //wait for pods to startup
        doCheck=true
        failures = 0
        while(doCheck) {
            try {
                //5 minutes timeout before restarting watch
                script.timeout(5) {
                    openshift.selector('dc', labels).watch {
                        boolean allDone = true
                        it.withEach { item ->
                            def dc = item.object()
                            script.echo "${key(dc)} - desired:${dc?.status?.replicas}  ready:${dc?.status?.readyReplicas} available:${dc?.status?.availableReplicas}"
                            if (!(dc?.status?.replicas == dc?.status?.readyReplicas && dc?.status?.replicas == dc?.status?.availableReplicas)) {
                                allDone = false
                            }
                        }
                        return allDone
                    }
                }
            } catch (ex){
                failures++
                script.echo "${stackTraceAsString(ex)}"
                //after 10 failures, give up
                if (failures > 10){
                    throw ex
                }
                continue
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
                /*
                for (Map object:it.objects(exportable:true)){
                    if (!isBuildComplete(object)) {
                        script.echo "${key(object)} - ${object.status.phase}"
                        allDone = false
                    }
                }
                */
                
                it.withEach { item ->
                    try {
                        def object = item.object()
                        if (!isBuildComplete(object)) {
                            script.echo "${key(object)} - ${object.status.phase}"
                            allDone = false
                        }
                    }catch (ex){
                        script.echo "${stackTraceAsString(ex)}"
                        //This can happen when the script waits for so long
                        // that a build object may just have been pruned/deleted
                        return false
                    }
                }
                
                return allDone
            }
            script.sleep 5
            doCheck=false
            try {
                for (Map build:openshift.selector('builds', labels).objects()){
                    if (!isBuildComplete(build)) {
                        doCheck = true
                        break
                    }
                } //end for
            }catch (ex){
                script.echo "${stackTraceAsString(ex)}"
                //This can happen when the script waits for so long
                // that a build object may just have been pruned/deleted
                doCheck = true
            }
        } //end while
        //openshift.verbose(false)
    }

    private void checkProjectsAccess(CpsScript script, OpenShiftDSL openshift, Map context){
        String currentUser= openshift.raw('whoami').out.tokenize()[0]
        String currentProjectName= openshift.project()
        String currentProjectBaseName = null

        if (currentProjectName.endsWith('-tools')){
            currentProjectBaseName=currentProjectName.substring(0, currentProjectName.length()-6)
        }

        script.echo "currentProjectBaseName: '${currentProjectBaseName}'"

        Map modifiedEnvProjects=[:]

        script.waitUntil {
            boolean isReady = true
            List projects=[]
            List accessibleProjects=openshift.raw('projects', '-q').out.tokenize()
            for(String envKeyName: context.env.keySet() as String[]){
                Map env=context.env[envKeyName]
                if (env.project!=null) {
                    projects.add(env.project)
                }else if (env.project == null && currentProjectBaseName!=null){
                    String deployProjectName="${currentProjectBaseName}-deploy"
                    String envProjectName="${currentProjectBaseName}-${envKeyName.toLowerCase()}"
                    boolean deployProjectAccessible = accessibleProjects.contains(deployProjectName)
                    boolean envProjectAccessible = accessibleProjects.contains(envProjectName)

                    script.echo "deployProjectName:'${deployProjectName}' accessible:${deployProjectAccessible}"
                    script.echo "envProjectName:'${envProjectName}' accessible:${envProjectAccessible}"

                    if (deployProjectAccessible){
                        modifiedEnvProjects[envKeyName]=deployProjectName
                    }else{
                        modifiedEnvProjects[envKeyName]=envProjectName
                    }

                    projects.add(modifiedEnvProjects[envKeyName])
                }else if (env.project == null){
                    modifiedEnvProjects[envKeyName]="${currentProjectName}"
                    projects.add(modifiedEnvProjects[envKeyName])
                }
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

        for(String envKeyName: modifiedEnvProjects.keySet() as String[]){
            script.echo "Setting target project for '${envKeyName}' to '${modifiedEnvProjects[envKeyName]}' "
            context.env[envKeyName].project=modifiedEnvProjects[envKeyName]
        }
        //script.error "stop here"
    }

    def prepareForCD(CpsScript script, Map context) {
        //Prepare status for deployments
        for(String envKeyName: context.env.keySet() as String[]){
            new GitHubHelper().createCommitStatus(script, context.commitId, 'PENDING', "${script.env.BUILD_URL}", "Deployment to ${envKeyName.toUpperCase()}", "continuous-integration/jenkins/deployment/${envKeyName.toLowerCase()}")
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

        script.echo "BRANCH_NAME=${script.env.BRANCH_NAME}\nCHANGE_ID=${script.env.CHANGE_ID}\nCHANGE_TARGET=${script.env.CHANGE_TARGET}\nBUILD_URL=${script.env.BUILD_URL}\nisPullRequestFromFork"
        script.echo "absoluteUrl=${script.currentBuild.absoluteUrl}"

        script.sh(returnStdout: false, script: "git log --pretty=oneline -20")
        script.sh(returnStdout: false, script: "git rev-list -20 HEAD")


        loadMetadata(script, context)

        script.echo "isPullRequestFromFork:${context.isPullRequestFromFork}"

        new GitHubHelper().createCommitStatus(script, context.commitId, 'PENDING', "${script.env.BUILD_URL}", 'Build', 'continuous-integration/jenkins/build')

        context['ENV_KEY_NAME'] = 'build'
        script.stash(name: 'openshift', includes:stashIncludes.join(','))
        Map labels=['app-name': context.name, 'env-name': context.buildEnvName]

        openshift.withCluster() {
            openshift.withProject(openshift.project()) {
                checkProjectsAccess(script, openshift, context)

                script.echo "Connected to project '${openshift.project()}' as user '${openshift.raw('whoami').out.tokenize()[0]}'"
                def newObjects = loadObjectsFromTemplate(openshift, context.templates.build, context, 'build')
                def currentObjects = loadObjectsByLabel(openshift, labels)
                //script.echo "${currentObjects}"
                for (Map m : newObjects.values()){
                    if ('BuildConfig'.equalsIgnoreCase(m.kind)){
                        // apply last commit id/hash to spec.source.git.ref
                        // this ensure that a build will get triggered only when there has been changes
                        
                        String commitId = context.commitId
                        String contextDir=null

                        if (m.spec && m.spec.source && m.spec.source.contextDir){
                            contextDir=m.spec.source.contextDir
                        }

                        if (contextDir!=null && contextDir.startsWith('/') && !contextDir.equalsIgnoreCase('/')){
                            contextDir=contextDir.substring(1)
                        }

                        if (!m.metadata.annotations) m.metadata.annotations=[:]
                        if (!m.metadata.labels) m.metadata.labels=[:]
                        m.metadata.annotations['source.git.commit']=commitId
                        
                        if (m.spec.source?.git?.uri){
                            if (m.spec.source.git.uri.equalsIgnoreCase(context.gitRepoUrl)){
                                commitId=getLastSha1InPath(m.spec.source.git.uri, context.gitBranchRemoteRef, contextDir?:'')
                                if (m.spec.source.git.ref) m.metadata.annotations['source/spec.source.git.ref']=m.spec.source.git.ref
                                m.metadata.annotations['source.git.ref']=context.gitBranchRemoteRef
                                m.metadata.annotations['source.git.head']=getLastSha1InPath(m.spec.source.git.uri, context.gitBranchRemoteRef, '')
                                m.metadata.annotations['source.git.commit']=commitId
                                m.spec.source.git.ref=commitId
                                if (context.isPullRequestFromFork) {
                                    m.metadata.annotations['source.git.commit']=m.metadata.annotations['source.git.head']
                                    m.spec.source.git.ref=context.gitBranchRemoteRef
                                }
                            }else{
                                commitId=getLastSha1InPath(m.spec.source.git.uri, m.spec.source.git.ref, contextDir?:'')
                                m.metadata.annotations['source.git.ref']=m.spec.source.git.ref
                                m.metadata.annotations['source.git.head']=getLastSha1InPath(m.spec.source.git.uri, m.spec.source.git.ref, '')
                                m.metadata.annotations['source.git.commit']=commitId
                                m.spec.source.git.ref=commitId
                            }
                        }
                        //m.metadata.labels['git-ref']=m.metadata.annotations['source.git.ref']
                        m.metadata.labels['git-commit']=m.metadata.annotations['source.git.commit']
                        //m.spec.source.git.ref=m.metadata.annotations['source.git.commit']
                        
                        m.spec.runPolicy = 'SerialLatestOnly'
                        m.spec.output.to.name=m.spec.output.to.name.tokenize(':')[0]+':'+context.buildEnvName
                        if (m.spec.source?.git?.uri){
                            script.echo "${key(m)} - ${m.spec.source.git.uri}#${m.spec?.source?.git?.ref} @ ${m.metadata.annotations['source.git.head']} - /${m?.spec?.source?.contextDir?:''} @ ${m.metadata.annotations['source.git.commit']}"
                        }else{
                            script.echo "${key(m)} - @ ${m.metadata.annotations['source.git.commit']}"
                        }
                        
                        // retrieve existing spec.triggers.imageChange.lastTriggeredImageID
                        // this will ensure that builds won't be triggered upon updating BuildConfig
                        if (m.spec?.triggers != null ){
                            Map current = currentObjects[key(m)]
                            if (current!=null){
                                //script.echo "${key(m)} - current triggers -> ${current.spec.triggers}"
                                //script.echo "${key(m)} - new triggers -> ${m.spec.triggers}"
                                for (Map t1:m.spec.triggers){
                                    if ('ImageChange'.equalsIgnoreCase(t1.type)){
                                        if (current.spec.triggers != null){
                                            for (Map t2:current.spec.triggers){
                                                if ('ImageChange'.equalsIgnoreCase(t2.type)){
                                                    if (
                                                        (t1.imageChange?.from == null && t2.imageChange?.from == null) ||
                                                        (
                                                            (t1.imageChange?.from != null && t2.imageChange?.from != null) &&
                                                            t1.imageChange.from.kind.equalsIgnoreCase(t2.imageChange.from.kind) &&
                                                            t1.imageChange.from.name.equalsIgnoreCase(t2.imageChange.from.name)
                                                        )
                                                    ){
                                                        t1.imageChange = t1.imageChange?:[:]
                                                        t1.imageChange.lastTriggeredImageID=t2.imageChange.lastTriggeredImageID
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            //script.echo "${key(m)} - triggers -> ${m.spec.triggers}"
                        } //end fix (m.spec.triggers)
                    } //end if
                } // end for

                def initialBuildConfigState=loadBuildConfigStatus(openshift, labels)

                applyBuildConfig(script, openshift, context.name, context.buildEnvName, newObjects, currentObjects);
                script.echo "Waiting for builds to complete"
                waitForBuildsToComplete(script, openshift, labels)
                def startedNewBuilds=false
                def postBuildConfigState=loadBuildConfigStatus(openshift, labels)

                for (Map item: postBuildConfigState.values()){
                    if ('BuildConfig'.equalsIgnoreCase(item.kind)){
                        String lastBuildName="Build/${item.metadata.name}-${item.status.lastVersion}"
                        Map lastBuild=postBuildConfigState[lastBuildName]
                        script.echo "Analyzing if ${key(item)} needs a new build (last build is '${lastBuildName}')"
                        if (lastBuild !=null) {
                            script.echo "   Based on ${key(lastBuild)} with status ${lastBuild.status.phase}"
                        }else{
                            script.echo "   Based on last build not found"
                        }
                        def newBuild=null;
                        if (lastBuild == null) {
                            script.echo "   Starting a new build because none was found"
                            newBuild = openshift.selector(key(item)).startBuild()
                        }else if (lastBuild != null && !isBuildSuccesful(lastBuild)){
                            script.echo "   Starting a new build because the last one (${key(lastBuild)}) was not successful (${lastBuild.status.phase})"
                            newBuild = openshift.selector(key(item)).startBuild()
                        }else{
                            Map m=newObjects[key(item)]
                            if (m!=null) {
                                if (lastBuild.spec?.revision?.git?.commit !=null){
                                    if (m.metadata?.labels['git-commit'] != null && !m.metadata.labels['git-commit'].equalsIgnoreCase(lastBuild.spec?.revision?.git?.commit)) {
                                        script.echo "   Starting a new build because the last commit (${lastBuild.spec?.revision?.git?.commit}) does not match latest one (${m.metadata.labels['git-commit']})"
                                        newBuild = openshift.selector(key(item)).startBuild()
                                    } else if (m.spec.source?.git?.uri) {
                                        String newestCommit = m.metadata.annotations['spec.source.git.ref']
                                        String oldestCommit = lastBuild.spec?.revision?.git?.commit

                                        if (newestCommit != null && !newestCommit.equalsIgnoreCase(oldestCommit)) {
                                            if (context.isPullRequestFromFork) {
                                                //git rev-list [newer] ^[older] --count
                                                int distance = Integer.parseInt(script.sh(returnStdout: true, script: "git rev-list ${newestCommit} ^${oldestCommit} --count").trim())
                                                script.echo "${distance} commits between ${oldestCommit} (oldest)  and ${newestCommit} (newest)"
                                                if (distance > 0) {
                                                    script.echo "   Starting a new build because the last one (${key(lastBuild)}) was outdated"
                                                    newBuild = openshift.selector(key(item)).startBuild()
                                                    startedNewBuilds = true
                                                }
                                            }
                                        }
                                    } else {
                                        script.echo "   Not starting a build (relying on ConfigChange/ImageChange triggers)"
                                        //startedNewBuilds = true
                                    }
                                }else{
                                    script.echo "   This build is not based on a GIT repository (relying on ConfigChange/ImageChange triggers)"
                                }
                                //git rev-list e71492589b94239576a6397997c29e6cb5b55fc8 ^e71492589b94239576a6397997c29e6cb5b55fc8 --count
                            }else{
                                script.echo "   ${key(item)} was not found among objects managed by the template(s). I am guessing it was removed, eh?"
                            }
                        }

                        if (newBuild!=null){
                            startedNewBuilds = true
                            script.echo "New build started - ${newBuild.name()}"
                        }
                    }
                }
                /*
                for (Map item: initialBuildConfigState.values()){
                    //script.echo "${item}"
                    if ('BuildConfig'.equalsIgnoreCase(item.kind)){
                        Map newItem=postBuildConfigState[key(item)]
                        Map build=initialBuildConfigState["Build/${item.metadata.name}-${item.status.lastVersion}"]

                        if (item.status.lastVersion == newItem.status.lastVersion && (build==null || !isBuildSuccesful(build))){
                            openshift.selector(key(item)).startBuild()
                            startedNewBuilds=true
                        }else if(build!=null){
                            //git rev-list [newer] ^[older] --count
                            //git rev-list e71492589b94239576a6397997c29e6cb5b55fc8 ^e71492589b94239576a6397997c29e6cb5b55fc8 --count
                        }

                    }
                }
                */

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
                    //script.echo "is --> ${iso}"
                    def tags=[:]
                    for (Map tag:iso.status.tags){
                        tags[tag.tag]=[
                            'items':[tag.items[0]]
                        ]
                    }
                    //script.echo "is.status.tags --> ${tags}"
                    
                    buildOutput["${key(iso)}"] = [
                            'kind': iso.kind,
                            'metadata': ['name':iso.metadata.name, 'namespace':iso.metadata.namespace],
                            'labels':iso.metadata.labels,
                            'status':[
                                'tags':tags
                            ]
                    ]
                    String baseName=getImageStreamBaseName(iso)
                    buildOutput["BaseImageStream/${baseName}"]=[
                        'ImageStream':key(iso)
                    ]
                }

                context['build'] = ['status':buildOutput, 'projectName':"${openshift.project()}"]


            }// enf withProject
        } // end withCluster
        new GitHubHelper().createCommitStatus(script, context.commitId, 'SUCCESS', "${script.env.BUILD_URL}", 'Build', 'continuous-integration/jenkins/build')
        context.deployments = context.deployments?:[:]
        context.deployments['build']=['projectName':context.build.projectName, 'labels':labels, 'transient':true]
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
                    //script.echo "Skipping '${key(o)}'"
                    //updates.add(o)
                    patches.add(o)
                }else{
                    //script.echo "Skipping '${o.kind}/${o.metadata.name}' (Already Exists)"
                    //def newObject=o
                    //if (newObject.spec && newObject.spec.tags){
                    //    newObject.spec.remove('tags')
                    //}
                    //script.echo "Modified '${o.kind}/${o.metadata.name}' = ${newObject}"
                    updates.add(o)
                }
            }

        }

        if (creations.size()>0){
            script.echo "Creating ${creations.size()} objects"
            openshift.apply(creations, '');
        }
        
        if (patches.size()>0){
            script.echo "Updating ${patches.size()} objects"
            openshift.apply(patches);
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
        script.unstash(name: 'openshift')
        initializeDeploymentContext(script, openshift, context, envKeyName)

        script.waitUntil {
            boolean isReady=false
            List errors = []
            try {
                Map deployCfg = context.deploy
                openshift.withCluster() {
                    openshift.withProject(deployCfg.projectName) {
                        Map models = loadObjectsFromTemplate(openshift, context.templates.deployment, context, 'deployment')

                        for (Map m : models.values()) {
                            Map annotations=m?.metadata?.annotations?:[:]
                            script.echo "Checking '${key(m)}'"
                            script.echo "  annotations:${annotations}"
                            if ("Route".equalsIgnoreCase(m.kind)) {
                                String secretName=(annotations[ANNOTATION_ROUTE_TLS_SECRET_NAME+".${envKeyName}"])?:(annotations[ANNOTATION_ROUTE_TLS_SECRET_NAME])

                                if (secretName!=null) {
                                    def selector = openshift.selector("secrets/${secretName}")
                                    if (selector.count() == 0) {
                                        errors.add("Missing 'secret/${secretName}'")
                                    }
                                }
                            }else if ("Secret".equalsIgnoreCase(m.kind) || "ConfigMap".equalsIgnoreCase(m.kind)) {
                                String sourceName=annotations[ANNOTATION_AS_COPY_OF+".${envKeyName}"]?:annotations[ANNOTATION_AS_COPY_OF]
                                if (sourceName!=null){
                                    def selector = openshift.selector("${m.kind}/${sourceName}")
                                    if (selector.count() == 0) {
                                        errors.add("Missing '${m.kind}/${sourceName}'")
                                    }
                                }
                            }
                        }

                    } //end withProject
                } // end withCluster
                isReady = errors.size() == 0
            } catch (ex) {
                script.echo "Error: ${ex}"
                isReady = false
            }

            if (!isReady){
                for (String err:errors){
                    script.echo "${err}"
                }
                script.input "Retry Environment Readiness Check?"
            }

            return isReady
        }

        clearDeploymentContext(script, openshift, context, envKeyName)
    }

    private void initializeDeploymentContext(CpsScript script, OpenShiftDSL openshift, Map context, String envKeyName) {
        Map deployCfg = createDeployContext(script, context, envKeyName)
        context['deploy'] = deployCfg
        context['DEPLOY_ENV_NAME'] = envKeyName
        context.deployments = context.deployments?:[:]
        context.deployments[envKeyName] = deployCfg
    }

    private void clearDeploymentContext(CpsScript script, OpenShiftDSL openshift, Map context, String envKeyName) {
        context.remove('deploy')
        context.remove('DEPLOY_ENV_NAME')
    }
    private Map createDeployContext(CpsScript script, Map context, String envKeyName) {
        String envName = envKeyName.toLowerCase()
        boolean transientEnv =false
        if ("DEV".equalsIgnoreCase(envKeyName)) {
            envName = "dev-pr-${script.env.CHANGE_ID}"
            transientEnv=true
        }
        Map deployCfg = [
                'envName':envName,
                'projectName':context.env[envKeyName].project,
                'envKeyName':envKeyName,
                'transient': transientEnv,
                'logUrl': "${script.env.BUILD_URL}"
        ]

        if (!deployCfg.dcPrefix) deployCfg.dcPrefix = context.name
        if (!deployCfg.dcSuffix) deployCfg.dcSuffix = "-${deployCfg.envName}"

        deployCfg['labels']=['app-name':context.name, 'env-name':envName]

        return deployCfg
    }

    @NonCPS
    List labelsToArgs(Map labels) {
        List args=[]
        labels.each { String key, String value ->
            args.addAll(['-l', "${key}=${value}"])
        }
        return args
    }

    void cleanup(CpsScript script, Map context) {
        OpenShiftDSL openshift=script.openshift
        for (Map deployment:context.deployments.values()){
            if (deployment.transient == true){
                openshift.withCluster(){
                    openshift.withProject(deployment.projectName) {
                        def result=openshift.delete((['all'] + labelsToArgs(deployment.labels)) as String[])
                        script.echo "Output:\n${result.out}"

                        def protectedSelector=openshift.selector('secret,configmap,pvc', deployment.labels)
                        if (protectedSelector.count() > 0) {
                            script.echo "Deleting: ${protectedSelector.names()}"
                            result=openshift.delete((['secret,configmap,pvc'] + labelsToArgs(deployment.labels)) as String[])
                            script.echo "Output:\n${result.out}"
                        }
                    } // end withProject
                } // end withCluster
            }
        }
    }

    void deploy(CpsScript script, Map context, String envKeyName) {
        OpenShiftDSL openshift=script.openshift
        initializeDeploymentContext(script, openshift, context, envKeyName)

        Map deployCfg = context.deploy
        script.echo "Deploying to ${envKeyName.toUpperCase()} as ${deployCfg.envName}"
        //GitHubHelper.getPullRequest(script).getHead().getSha()

        def ghDeploymentId = new GitHubHelper().createDeployment(script, context.commitId, ['environment':"${envKeyName.toUpperCase()}", 'payload':toJsonString(deployCfg), 'task':"deploy:pull:${script.env.CHANGE_ID}"])
        deployCfg['ghDeploymentId'] = ghDeploymentId

        new GitHubHelper().createDeploymentStatus(script, ghDeploymentId, 'PENDING', ['targetUrl':"${deployCfg.logUrl}"])

        new GitHubHelper().createCommitStatus(script, context.commitId, 'PENDING', "${deployCfg.logUrl}", "Deployment to ${envKeyName.toUpperCase()}", "continuous-integration/jenkins/deployment/${envKeyName.toLowerCase()}")

        //try {
            //GitHubHelper.getPullRequest(script).comment("Build in progress")
            //GitHubHelper.getPullRequest(script).comment("Deploying to DEV")

            script.unstash(name: 'openshift')
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
            context.deployments = context.deployments?:[:]
            context.deployments[envKeyName]=deployCfg
            new GitHubHelper().createDeploymentStatus(script, ghDeploymentId, 'SUCCESS', ['targetUrl':"${deployCfg.environmentUrl}"])
            new GitHubHelper().createCommitStatus(script, context.commitId, 'SUCCESS', "${deployCfg.logUrl}", "Deployment to ${envKeyName.toUpperCase()}", "continuous-integration/jenkins/deployment/${envKeyName.toLowerCase()}")
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

    private void applyDeploymentConfig(CpsScript script, OpenShiftDSL openshift, Map context) {
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
        List replaces=[]

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
            String sourceImage=sourceImageStream.status.tags[context.buildEnvName].items[0].image
            String sourceImageStreamRef="${sourceImageStream.metadata.namespace}/${sourceImageStream.metadata.name}@${sourceImage}"
            String targetImageStreamRef="${m.metadata.name}:${labels['env-name']}"
            String tempImageTagName="tmp-${labels['env-name']}"
            //The 2 steps tagging (import and tag) is required to create a `ImageStreamImage` that is local to the target project
            
            script.echo "Importing Image '${sourceImageStreamRef}' as '${m.metadata.name}:${tempImageTagName}'"
            openshift.raw('import-image', "${m.metadata.name}:${tempImageTagName}", "--from=docker-registry.default.svc:5000/${sourceImageStream.metadata.namespace}/${sourceImageStream.metadata.name}@${sourceImage}", '--insecure=true', '--confirm=true')

            script.echo "Tagging '${m.metadata.name}@${sourceImage}' as '${targetImageStreamRef}'"
            openshift.tag("${m.metadata.name}@${sourceImage}", targetImageStreamRef)

            script.echo "Deleting temporary tag: '${m.metadata.name}:${tempImageTagName}'"
            openshift.tag("${m.metadata.name}:${tempImageTagName}", '-d')
            
            //script.echo "Tagging '${sourceImageStreamRef}' as '${targetImageStreamRef}'"
            //openshift.tag(sourceImageStreamRef, targetImageStreamRef)
        }
        script.echo "Applying Configurations"
        upserts.clear()
        for (Map m : models.values()) {
            Map annotations=m?.metadata?.annotations?:[:]
            if ("Route".equalsIgnoreCase(m.kind)) {
                String secretName=(annotations[ANNOTATION_ROUTE_TLS_SECRET_NAME+".${deployCtx.envKeyName}"])?:(annotations[ANNOTATION_ROUTE_TLS_SECRET_NAME])
                if (secretName!=null){
                    script.echo "Applying TLS using secret/${secretName} for '${key(m)}'"
                    m.spec.tls = m.spec.tls?:[:]
                    def selector=openshift.selector("secrets/${secretName}")
                    if (selector.count() == 1){
                        script.echo "Modifying '${key(m)}'"
                        Map secret=selector.object()
                        m.spec.tls.caCertificate=new String(secret.data.caCertificate.decodeBase64())
                        m.spec.tls.certificate=new String(secret.data.certificate.decodeBase64())
                        m.spec.tls.key=new String(secret.data.key.decodeBase64())
                    }
                }

                replaces.add(m)
            }else{
                String sourceName=annotations[ANNOTATION_AS_COPY_OF+".${deployCtx.envKeyName}"]?:annotations[ANNOTATION_AS_COPY_OF]
                if (sourceName!=null && sourceName.length()>0) {
                    script.echo "Creating a copy of '${m.kind}/${sourceName}' as '${key(m)}'"
                    def selector = openshift.selector("${m.kind}/${sourceName}")
                    if (selector.count() == 1) {
                        Map sourceModel=selector.object(exportable:true);
                        sourceModel.metadata.name=m.metadata.name
                        upserts.add(sourceModel)
                    }
                }else {
                    Map current = initDeploymemtConfigStatus[key(m)]
                    if (allowCreateOrUpdate(m, current)) {
                        upserts.add(m)
                    }
                }
            }
        }
        openshift.apply(upserts).label(['app':"${labels['app-name']}-${labels['env-name']}", 'app-name':labels['app-name'], 'env-name':labels['env-name']], "--overwrite")

        if (replaces.size()>0) {
            openshift.apply(replaces, '--force=true').label(['app':"${labels['app-name']}-${labels['env-name']}", 'app-name':labels['app-name'], 'env-name':labels['env-name']], "--overwrite")
        }

        waitForDeploymentsToComplete(script, openshift, labels)

        openshift.selector('route', labels + ['frontend':'true']).withEach {
            Map route= it.object()
            if (route.spec.tls){
                deployCtx['environmentUrl']= "https://${route.spec.host}${route.spec.path?:'/'}"
            }else{
                deployCtx['environmentUrl']= "http://${route.spec.host}${route.spec.path?:'/'}"
            }
        }


        //return loadDeploymentConfigStatus(openshift, labels)
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
