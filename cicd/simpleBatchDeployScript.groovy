
pipeline {
    agent any

    tools {
        gradle "Gradle 7.6"
        jdk "jdk17"
    }
    environment {
        GIT_DISTRIBUTE_URL = "https://github.com/devforlove/deploy.git"
    }
    stages {
        stage("Preparing Job") {
            steps {
                script {
                    try {
                        GIT_DISTRIBUTE_BRANCH_MAP = ["dev" : "develop", "qa" : "release", "prod" : "main"]

                        env.GIT_DISTRIBUTE_BRANCH = GIT_DISTRIBUTE_BRANCH_MAP[STAGE]

                        print("Deploy stage is ${STAGE}")
                        print("Deploy service is ${SERVICE}")
                        print("Deploy git branch is ${env.GIT_DISTRIBUTE_BRANCH}")
                    }
                    catch (error) {
                        print(error)
                        currentBuild.result = "FAILURE"
                    }
                }
            }
            post {
                failure {
                    echo "Preparing Job stage failed"
                }
                success {
                    echo "Preparing Job stage success"
                }
            }
        }
        stage("Cloning Git") {
            steps {
                script {
                    try {
                        git url: GIT_DISTRIBUTE_URL, branch: GIT_DISTRIBUTE_BRANCH, credentialsId: "GIT_CREDENTIAL"
                    }
                    catch (error) {
                        print(error)
                        currentBuild.result = "FAILURE"
                    }
                }
            }
            post {
                failure {
                    echo "Git clone stage failed"
                }
                success {
                    echo "Git clone stage success"
                }
            }
        }
        stage("Building Jar") {
            steps {
                script {
                    try {
                        sh("rm -rf deploy")
                        sh("mkdir deploy")

                        sh("gradle :${SERVICE}:clean :${SERVICE}:build -x test")

                        sh("cp /var/jenkins_home/workspace/${env.JOB_NAME}/${SERVICE}/build/libs/*.jar ./deploy/${SERVICE}.jar")
                        sh("cp /var/jenkins_home/workspace/${env.JOB_NAME}/${SERVICE}/codedeploy/appspec.yml ./deploy")
                        sh("cp /var/jenkins_home/workspace/${env.JOB_NAME}/${SERVICE}/codedeploy/deploy.sh ./deploy")
                        zip(
                            zipFile: "deploy.zip",
                            dir: "/var/jenkins_home/workspace/${env.JOB_NAME}/deploy"
                        )
                    }
                    catch (error) {
                        print(error)
//                        sh("rm -rf /var/jenkins_home/workspace/${env.JOB_NAME}/*")
                        currentBuild.result = "FAILURE"
                    }
                }
            }
            post {
                failure {
                    echo "Build jar stage failed"
                }
                success {
                    echo "Build jar stage success"
                }
            }
        }
        stage("Upload To S3") {
            steps {
                script {
                    try {
                        withAWS(credentials: "AWS_CREDENTIAL") {
                            s3Upload(
                                    path: "${env.JOB_NAME}/${env.BUILD_NUMBER}/${env.JOB_NAME}.zip",
                                    file: "/var/jenkins_home/workspace/${env.JOB_NAME}/deploy.zip",
                                    bucket: "batch-repo"
                            )
                        }
                    }
                    catch (error) {
                        print(error)
//                        sh("rm -rf /var/jenkins_home/workspace/${env.JOB_NAME}/*")
                        currentBuild.result = "FAILURE"
                    }
                }
            }
        }
        stage("Deploy") {
            steps {
                script {
                    try {
                        withAWS(credentials: "AWS_CREDENTIAL") {
                            createDeployment(
                                    s3Bucket: 'batch-repo',
                                    s3Key: "${env.JOB_NAME}/${env.BUILD_NUMBER}/${env.JOB_NAME}.zip",
                                    s3BundleType: 'zip',
                                    applicationName: 'batch-deploy',
                                    deploymentGroupName: 'batch-deploy-group',
                                    deploymentConfigName: 'CodeDeployDefault.AllAtOnce',
                                    description: 'Batch deploy',
                                    waitForCompletion: 'true',
                                    //Optional values
                                    ignoreApplicationStopFailures: 'false',
                                    fileExistsBehavior: 'OVERWRITE'// [Valid values: DISALLOW, OVERWRITE, RETAIN]
                            )
                        }
                    }
                    catch (error) {
                        print(error)
//                        sh("sudo rm -rf /var/jenkins_home/workspace/${env.JOB_NAME}/*")
                        currentBuild.result = "FAILURE"
                    }
                }
            }
            post {
                failure {
                    echo "Deploy stage failed"
                }
                success {
                    echo "Deploy stage success"
                }
            }
        }
        stage("Clean Up") {
            steps {
                script {
                    sh("sudo rm -rf /var/jenkins_home/workspace/${env.JOB_NAME}/*")
                }
            }
        }
    }
}