
pipeline {
    agent any

    tools {
        gradle "Gradle 7.6"
        jdk "jdk17"
    }
    environment {
        GIT_DISTRIBUTE_URL = "https://github.com/junginwook/multimodule.git"
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

                        sh("gradle clean ${SERVICE}:build -x test")

                        sh("cd deploy")
                        sh("cp /var/lib/jenkins/workspace/${env.JOB_NAME}/${SERVICE}/build/libs/*.jar ./deploy/${SERVICE}.jar")
                    }
                    catch (error) {
                        print(error)
                        sh("sudo rm -rf /var/lib/jenkins/workspace/${env.JOB_NAME}/*")
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


                        def script = """
                        #!/bin/bash
                        kill -9 `pgrep -f ${SERVICE}.jar`
                        nohup java -jar /home/ec2-user/deploy/${SERVICE}.jar 1> /dev/null 2>&1 &
                        """.stripIndent()
                        writeFile(file: 'deploy/deploy.sh', text: script)

                        sh """
                        cd deploy
                        cat>appspec.yml<<-EOF
                        version: 0.0
                        os: linux
                        files:
                          - source:  /
                            destination: /home/ec2-user/deploy
                        
                        permissions:
                          - object: /
                            pattern: "**"
                            owner: ec2-user
                            group: ec2-user
                        
                        hooks:
                          ApplicationStart:
                            - location: deploy.sh
                              timeout: 60
                              runas: root
                        """.stripIndent()

                        sh """
                        cd deploy
                        zip -r deploy *
                        """


                        withAWS(credentials: "AWS_CREDENTIAL") {
                            s3Upload(
                                    path: "${env.JOB_NAME}/${env.BUILD_NUMBER}/${env.JOB_NAME}.zip",
                                    file: "/var/lib/jenkins/workspace/${env.JOB_NAME}/deploy/deploy.zip",
                                    bucket: "inwook-beanstalk-deploy"
                            )
                        }
                    }
                    catch (error) {
                        print(error)
                        sh("sudo rm -rf /var/lib/jenkins/workspace/${env.JOB_NAME}/*")
                        currentBuild.result = "FAILURE"
                    }
                }
            }
            post {
                failure {
                    echo "Upload S3 stage failed"
                }
                success {
                    echo "Upload S3 stage success"
                }
            }
        }
        stage("Deploy") {
            steps {
                script {
                    try {
                        withAWS(credentials: "AWS_CREDENTIAL") {
                            sh """
                                aws deploy create-deployment \
                                --application-name inwook-deploy \
                                --deployment-group-name inwook-deploy-group \
                                --region ap-northeast-2 \
                                --s3-location bucket=inwook-beanstalk-deploy,key=${env.JOB_NAME}/${env.BUILD_NUMBER}/${env.JOB_NAME}.zip,bundleType=zip \
                                --file-exists-behavior OVERWRITE \
                                --output json > DEPLOYMENT_ID.json
                                cat DEPLOYMENT_ID.json
                            """
                        }

                        def DEPLOYMENT_ID = readJSON file: './DEPLOYMENT_ID.json'
                        echo"${DEPLOYMENT_ID.deploymentId}"
                        sh("rm -rf ./DEPLOYMENT_ID.json")

                        awaitDeploymentCompletion("${DEPLOYMENT_ID.deploymentId}")
                    }
                    catch (error) {
                        print(error)
                        sh("sudo rm -rf /var/lib/jenkins/workspace/${env.JOB_NAME}/*")
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
                    try {

                    }
                    catch (error) {
                        print(error)
                        currentBuild.result = "FAILURE"
                    }
                }
            }
        }
    }
}
