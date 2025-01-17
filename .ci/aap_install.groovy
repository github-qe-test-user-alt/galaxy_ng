@Library(['aap-jenkins-shared-library@galaxy_ng_yolo']) _
import steps.StepsFactory
import validation.AapqaProvisionerParameters

StepsFactory stepsFactory = new StepsFactory(this, [:], 'aap_galaxy_ng')
Map provisionInfo = [:]
Map installInfo = [:]
Map towerqaSetupInfo = [:]
Map ansibleUIinfo = [:]
Map validateInfo = [:]
List installerFlags = []
Map installerVars = [:]
String pulpcore_version = ''
String automationhub_pulp_ansible_version = ''
String automationhub_pulp_container_version = ''
String fork = 'ansible'

pipeline {
        agent {
            kubernetes {
                yaml libraryResource('pod_templates/unpriv-ansible-pod.yaml')
            }
        }
        options {
            ansiColor('xterm')
            timestamps()
            timeout(time: 18, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '50', artifactNumToKeepStr: '40'))
        }
        
        stages {
            stage('Validate') {
                steps {
                    script {

                        echo "GitHub Repository: ${env.GITHUB_REPO}"
                        echo "GitHub Fork: ${env.GITHUB_FORK}"
                        fork = env.GITHUB_FORK ?: 'ansible'
                        echo "${fork}"
                        echo "Branch Name: ${env.BRANCH_NAME}"
                            
                        // validateInfo = stepsFactory.yoloSteps.validateYoloParameters(params)

                        List provisionFlags = []

                        installerFlags.add('input/install/flags/automationhub_content_signing.yml')
                        installerFlags.add('input/install/flags/automationhub_routable_hostname.yml')
                        installerFlags.add('input/install/flags/automationhub_from_git.yml')

                        provisionFlags.add('input/provisioner/flags/domain.yml')
                        provisionFlags.add("input/provisioner/architecture/x86_64.yml")
                        provisionFlags.add('input/provisioner/flags/domain.yml')

                        validateInfo.put("provisionFlags", provisionFlags)
                        validateInfo.put("installerFlags", installerFlags)
                    }
                }
            }

            stage('Checkout galaxy_ng repo') {
                steps {
                    container('aapqa-ansible') {
                        script {
                                stepsFactory.commonSteps.checkoutGalaxyNG([galaxyNGBranch: env.BRANCH_NAME,  galaxyNGFork: fork])
                        }
                    }
                }
            }

            stage('Get pulpcore, pulp_ansible, pulp-container versions from setup.py') {
                steps {
                    container('aapqa-ansible') {
                        script {
                            def setupPyContent = readFile('setup.py').trim()
                            def lines = setupPyContent.split('\n')
                            def dependenciesToExtract = ["pulpcore", "pulp_ansible", "pulp-container"]
                            def minimumVersions = [:]
                            lines.each { line ->
                                dependenciesToExtract.each { dependency ->
                                    if (line.contains("$dependency>=")) {
                                        def versionMatch = line =~ /$dependency>=([\d.]+)/
                                        if (versionMatch) {
                                            minimumVersions[dependency] = versionMatch[0][1]
                                        }
                                    }
                                }
                            }

                            dependenciesToExtract.each { dependency ->
                                if (minimumVersions.containsKey(dependency)) {
                                    println("Using $dependency version: ${minimumVersions[dependency]}")
                                } else {
                                    println("$dependency not found in setup.py. Using version defined in the installer")
                                }
                            }
                            if (minimumVersions.containsKey("pulpcore")){
                                pulpcore_version = minimumVersions["pulpcore"]
                            } 
                            if (minimumVersions.containsKey("pulp_ansible")){
                                automationhub_pulp_ansible_version = minimumVersions["pulp_ansible"]
                            }
                            if (minimumVersions.containsKey("pulp-container")){
                                automationhub_pulp_container_version = minimumVersions["pulp-container"]
                            }
                        }
                    }
                }
                
            }

            stage('Setup aapqa-provisioner') {
                steps {
                    container('aapqa-ansible') {
                        script {
                            stepsFactory.aapqaSetupSteps.setup()
                        }
                    }
                }
            }

            stage('Provision') {
                steps {
                    container('aapqa-ansible') {
                        script {
                            provisionInfo = [
                                    provisionerPrefix: validateInfo.provisionPrefix,
                                    cloudVarFile     : "input/provisioner/cloud/aws.yml",
                                    scenarioVarFile  : "input/aap_scenarios/1inst_1hybr_1ahub.yml",
                            ]
                            provisionInfo = stepsFactory.aapqaOnPremProvisionerSteps.provision(provisionInfo + [
                                    provisionerVarFiles: validateInfo.get("provisionFlags") + [
                                            "input/platform/rhel88.yml",
                                    ],
                                    isPermanentDeploy  : false,
                                    registerWithRhsm   : true,
                                    runMeshScalingTests: false,
                                    runInstallerTests  : false
                            ])
                        }
                    }
                }
                post {
                    always {
                        script {
                            stepsFactory.aapqaOnPremProvisionerSteps.archiveArtifacts()
                        }
                    }
                }
            }
            
            stage('Install') {
                steps {
                    container('aapqa-ansible') {
                        script {
                            installerFlags = validateInfo.get("installerFlags")
      
                            installerVars = [:]

                            Map ahubPipParams = [
                                    automationhub_git_url: "https://github.com/${fork}/galaxy_ng",
                                    automationhub_git_version: "${env.BRANCH_NAME}",
                                    automationhub_ui_download_url: "https://github.com/ansible/ansible-hub-ui/releases/download/dev/automation-hub-ui-dist.tar.gz",
                            ]
                            if (pulpcore_version != '') {
                                ahubPipParams['pulpcore_version'] = "${pulpcore_version}"
                                println("Using pulpcore version: ${pulpcore_version}")
                            }else{
                                println("pulpcore_version version not provided, using version defined in the installer")
                            }
                            if (automationhub_pulp_ansible_version != '') {
                                ahubPipParams['automationhub_pulp_ansible_version'] = "${automationhub_pulp_ansible_version}"
                                println("Using pulp_ansible version: ${automationhub_pulp_ansible_version}")
                            }else{
                                println("pulp_ansible version not provided, using version defined in the installer")
                            }
                            if (automationhub_pulp_container_version != '') {
                                ahubPipParams['automationhub_pulp_container_version'] = "${automationhub_pulp_container_version}"
                                println("Using pulp-container version: ${automationhub_pulp_container_version}")
                            }else{
                                println("pulp-container version not provided, using version defined in the installer")
                            }

                            writeYaml(
                                    file: 'input/install/ahub_pip.yml',
                                    data: ahubPipParams
                            )
                            installerFlags.add('input/install/ahub_pip.yml')
                            archiveArtifacts(artifacts: 'input/install/ahub_pip.yml')
                            
                            installInfo = stepsFactory.aapqaAapInstallerSteps.install(provisionInfo + [
                                aapVersionVarFile: "input/install/2.4_released.yml",
                                installerVarFiles: installerFlags + [
                                    "input/aap_scenarios/1inst_1hybr_1ahub.yml",
                                    "input/platform/rhel88.yml"
                                ],
                                installerVars: installerVars
                            ])
                        }
                    }
                }

                post {
                    always {
                        script {
                            container('aapqa-ansible') {
                                stepsFactory.aapqaAapInstallerSteps.collectAapInstallerArtifacts(provisionInfo + [
                                        archiveArtifactsSubdir: 'install'
                                ])

                                if (fileExists('artifacts/install/setup.log')) {
                                    sh """
                                        echo "Install setup log:"
                                        echo "-------------------------------------------------"
                                        cat artifacts/install/setup.log
                                        echo "-------------------------------------------------"
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Run AutomationHub Tests') {
                steps {
                    container('aapqa-ansible') {
                        script {

                            stepsFactory.aapqaAutomationHubSteps.setup(installInfo + [galaxyNgFork: "chr-stian", galaxyNgBranch: "installer_smoke_test"])
                            stepsFactory.aapqaAutomationHubSteps.runAutomationHubSuite(installInfo + [ahubTestExpression: "installer_smoke_test"])
                            stepsFactory.commonSteps.saveXUnitResultsToJenkins(xunitFile: 'ah-results.xml')
                            stepsFactory.aapqaAutomationHubSteps.reportTestResults(provisionInfo + installInfo +
                                    [
                                            component: 'ahub',
                                            testType: 'api',
                                    ], "ah-results.xml")
                        }
                    }
                }
                post {
                    always {
                        container('aapqa-ansible') {
                            script {
                                stepsFactory.aapqaAutomationHubSteps.cleanup(installInfo)
                            }
                        }
                    }
                }
            }
            
        }

        post {
            always {
                container('aapqa-ansible') {
                    script {
                        stepsFactory.aapqaAapInstallerSteps.generateAndCollectSosReports(provisionInfo)
                    }
                }
            }
            cleanup {
                container('aapqa-ansible') {
                    script {
                        if (provisionInfo != [:]) {
                            stepsFactory.aapqaOnPremProvisionerSteps.cleanup(provisionInfo)
                        }
                    }
                }
            }
        }
    }
