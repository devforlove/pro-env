
pipeline {
    agent any

    tools {
        gradle "Gradle 7.6"
        jdk "jdk17"
    }
    environment {
        GIT_DISTRIBUTE_URL = "https://github.com/junginwook/multimodule.git"
        ECR_LOGIN_HELPER = "docker-credential-ecr-login"
        REGION = "ap-northeast-2"
        ECR_URL = "457516223683.dkr.ecr.ap-northeast-2.amazonaws.com"
        REPOSITORY = "test"
        DEPLOY_HOST = "172.31.39.96"
    }
    stages {
        stage('Preparing Job') {
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
        }
        stage('Clone codes from github') {
            steps {
                script {
                    try {
                        git url: GIT_DISTRIBUTE_URL, branch: GIT_DISTRIBUTE_BRANCH, credentialsId: "ssh-key"
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
        stage('Build codes by Gradle') {
            steps {
                sh("rm -rf deploy")
                sh("mkdir deploy")

                sh("gradle clean ${SERVICE}:build -x test")
                sh("cp /var/jenkins_home/workspace/${env.JOB_NAME}/${SERVICE}/build/libs/*.jar ./deploy/${SERVICE}.jar")
            }
        }
        stage('Building Docker Image by Jib & Push to Aws ECR repository') {
            steps {
                withAWS(region:"${REGION}", credentials:"aws-key") {
                    ecrLogin()
                    sh """
                        curl -O https://amazon-ecr-credential-helper-releases.s3.us-east-2.amazonaws.com/0.4.0/linux-amd64/${ECR_LOGIN_HELPER}
                        chmod +x ${ECR_LOGIN_HELPER}
                        mv ${ECR_LOGIN_HELPER} /usr/local/bin/
                        cd /var/jenkins_home/workspace/${env.JOB_NAME}
                        ./gradlew ${SERVICE}:jib -Djib.to.image=${ECR_URL}/${REPOSITORY}:${currentBuild.number} -Djib.console='plain'
                    """
                }
            }
        }
        stage('Deploy to AWS EC2 VM') {
            steps {
                sshagent(credentials : ["deploy-key"]) {
                    sh "ssh -o StrictHostKeyChecking=no ubuntu@${DEPLOY_HOST} \
                     'aws ecr get-login-password --region ${REGION} | docker login --username AWS --password-stdin ${ECR_URL}/${REPOSITORY}; \
                      docker run -d -p 80:8080 -t ${ECR_URL}/${REPOSITORY}:${currentBuild.number};'"
                }
            }
        }
        stage('Clean Up') {
            steps {
                echo "Clean Up"
            }
        }
    }
}