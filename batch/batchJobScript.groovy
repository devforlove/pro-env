
pipeline {
    agent any

    tools {
        gradle "Gradle 7.6"
        jdk "jdk17"
    }
    environment {
        ORIGIN_JAR= "\$(readlink /var/jenkins_home/batch/application.jar)"
    }
    stages {
        stage("Start Job") {
            steps {
                script {
                    try {
                        sh """
                            cd /var/jenkins_home/batch
                            java -jar ${env.ORIGIN_JAR} --job.name=${JOB_NAME} version=${VERSION}  
                        """.stripIndent()
                    } catch(error) {
                        print(error)
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
    }
}