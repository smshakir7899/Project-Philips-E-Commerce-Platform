// =============================================================================================
// Philips e-commerce - declarative CI/CD pipeline.
//
//  Stage               | Runs on any agent with JDK 8 + Docker? | Extra requirement
//  --------------------+----------------------------------------+-----------------------------------------
//  Checkout            | yes                                    | -
//  Build               | yes                                    | JDK tool 'jdk8' (Maven via ./mvnw)
//  Unit Tests          | yes                                    | JUnit + JaCoCo plugins (reports)
//  Integration Tests   | needs a Docker daemon (Testcontainers) | param RUN_INTEGRATION_TESTS
//  Package             | yes                                    | -
//  SonarQube Analysis  | no                                     | SonarQube server 'sonarqube' + token, JDK tool 'jdk17', param RUN_SONAR
//  Quality Gate        | no                                     | SonarQube webhook -> <jenkins>/sonarqube-webhook/
//  Docker Build        | needs Docker CLI + daemon              | -
//  Docker Push         | no                                     | credential 'docker-registry-credentials', env DOCKER_REGISTRY, param PUSH_IMAGES
//  Deploy to EC2       | no                                     | credentials 'ec2-ssh-key' + 'ec2-prod-env', env EC2_HOST, param DEPLOY_TO_EC2
//
// Manage Jenkins > Tools (names must match exactly):  JDK 'jdk8', JDK 'jdk17', Maven 'maven-3.9'.
// The local Jenkins image (docker/jenkins/Dockerfile) contains all three; see deploy/jenkins/README.md.
// Global environment (Manage Jenkins > System > Global properties) or job env:
//   DOCKER_REGISTRY  e.g. 123456789012.dkr.ecr.eu-west-1.amazonaws.com   (no trailing slash)
//   EC2_HOST         public DNS / IP of the instance
//   DOCKER_PLATFORM  optional, e.g. linux/amd64 when the agent is arm64 but EC2 is x86_64
// No secret is ever written in this file - everything comes from Jenkins credentials.
// =============================================================================================

// Application modules that produce a Docker image (common-lib is a plain library jar).
def MODULES = ['config-server', 'discovery-server', 'api-gateway', 'product-service', 'inventory-service',
               'cart-service', 'order-service', 'payment-service', 'notification-service']

pipeline {
    agent any

    tools {
        jdk 'jdk8'            // Java 8 bytecode, same JVM as the runtime images
        maven 'maven-3.9'     // fallback; the stages use the committed Maven wrapper (./mvnw)
    }

    parameters {
        booleanParam(name: 'RUN_INTEGRATION_TESTS', defaultValue: false, description: 'Run *IT tests (-Pintegration-tests, Testcontainers - Docker needed on the agent)')
        booleanParam(name: 'RUN_SONAR', defaultValue: true, description: 'SonarQube analysis + Quality Gate')
        booleanParam(name: 'PUSH_IMAGES', defaultValue: false, description: 'Push images to DOCKER_REGISTRY')
        booleanParam(name: 'DEPLOY_TO_EC2', defaultValue: false, description: 'Deploy to EC2_HOST (requires PUSH_IMAGES)')
        string(name: 'IMAGE_TAG', defaultValue: '', description: 'Image tag; empty = <build number>-<short commit>')
    }

    options {
        skipDefaultCheckout(true)          // explicit Checkout stage below
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds()          // one deploy at a time; also avoids port clashes in ITs
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
    }

    environment {
        // Batch mode, no download progress noise. The local repository stays in the agent user's
        // ~/.m2 (outside the workspace), so cleanWs() does not force a full re-download.
        MAVEN_ARGS = '-B -ntp'
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    def scmVars = checkout scm
                    env.GIT_COMMIT = scmVars.GIT_COMMIT
                    env.GIT_SHORT = scmVars.GIT_COMMIT ? scmVars.GIT_COMMIT.take(7) : 'nogit'
                    // A job parameter can't be overwritten through env.IMAGE_TAG, hence APP_TAG.
                    env.APP_TAG = params.IMAGE_TAG?.trim() ? params.IMAGE_TAG.trim() : "${env.BUILD_NUMBER}-${env.GIT_SHORT}"
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.APP_TAG}"
                }
                sh 'chmod +x mvnw && ./mvnw -v'
            }
        }

        stage('Build') {
            steps {
                // Compiles all modules; maven-enforcer fails here if any dependency is newer than Java 8.
                sh './mvnw $MAVEN_ARGS clean compile'
            }
        }

        stage('Unit Tests') {
            steps {
                // Surefire excludes *IT.java; JaCoCo agent + report (target/site/jacoco/jacoco.xml) run in the test phase.
                sh './mvnw $MAVEN_ARGS test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                    jacoco execPattern: '**/target/jacoco.exec',
                           classPattern: '**/target/classes',
                           sourcePattern: '**/src/main/java',
                           exclusionPattern: '**/*Application.class,**/config/**,**/dto/**,**/entity/**,**/event/**'
                }
            }
        }

        stage('Integration Tests') {
            when { expression { params.RUN_INTEGRATION_TESTS } }
            // Jenkins in a container using the host socket ("Docker outside of Docker"): the
            // Testcontainers-started containers are SIBLINGS, reachable through the host, not via
            // localhost. If ITs cannot connect, set TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
            // as a global env var (Docker Desktop; on Linux add extra_hosts "host.docker.internal:host-gateway").
            steps {
                sh 'docker version --format "Docker server {{.Server.Version}}"'
                // verify = unit tests again + failsafe (*IT.java) - simple and keeps coverage complete.
                sh './mvnw $MAVEN_ARGS -Pintegration-tests verify'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/failsafe-reports/*.xml'
                }
            }
        }

        stage('Package') {
            steps {
                // Produces <module>/target/<module>.jar (finalName = artifactId), used by the Dockerfiles.
                sh './mvnw $MAVEN_ARGS package -DskipTests'
                archiveArtifacts artifacts: '*/target/*.jar', fingerprint: true, allowEmptyArchive: false
            }
        }

        stage('SonarQube Analysis') {
            when { expression { params.RUN_SONAR } }
            // SonarQube 10.x requires the SCANNER to run on Java 17. Only the runtime of this stage
            // changes: the classes were compiled to Java 8 bytecode in earlier stages and
            // sonar:sonar does not recompile (sonar.java.source=8 in the pom drives the rules).
            tools { jdk 'jdk17' }
            steps {
                withSonarQubeEnv('sonarqube') {   // injects SONAR_HOST_URL + token from the server config
                    sh './mvnw $MAVEN_ARGS sonar:sonar -Dsonar.projectVersion=$APP_TAG'
                }
            }
        }

        stage('Quality Gate') {
            when { expression { params.RUN_SONAR } }
            steps {
                // Waits for SonarQube's webhook callback (Administration > Webhooks:
                // http://<jenkins>/sonarqube-webhook/). Without the webhook this times out.
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Docker Build') {
            steps {
                script {
                    // CPU architecture matters: an image built on an Apple Silicon (arm64) agent does not
                    // run on an x86_64 EC2 instance (t3.*) - "exec format error". Set the global env
                    // DOCKER_PLATFORM=linux/amd64 (t3) or linux/arm64 (Graviton t4g) to build for the target;
                    // Docker Desktop emulates the other architecture (fine here: the build only COPYs a jar).
                    def platformArg = env.DOCKER_PLATFORM?.trim() ? "--platform ${env.DOCKER_PLATFORM.trim()}" : ''
                    for (m in MODULES) {
                        // Context = module dir: only target/<module>.jar is copied into the image.
                        sh """
                           docker build ${platformArg} \
                             --label org.opencontainers.image.revision=${env.GIT_COMMIT ?: 'unknown'} \
                             --label org.opencontainers.image.version=${env.APP_TAG} \
                             -t philips-ecommerce/${m}:${env.APP_TAG} ${m}
                        """
                    }
                }
            }
        }

        stage('Docker Push') {
            when { expression { params.PUSH_IMAGES } }
            steps {
                script {
                    if (!env.DOCKER_REGISTRY?.trim()) {
                        error 'DOCKER_REGISTRY is not set (Manage Jenkins > System > Global properties)'
                    }
                }
                // Generic registry login with a "Username with password" credential.
                // ECR alternative (preferred on AWS - no long-lived secret): give the agent an IAM
                // role with ecr:GetAuthorizationToken + push permissions and replace the login with
                //   aws ecr get-login-password --region eu-west-1 | docker login --username AWS --password-stdin "$DOCKER_REGISTRY"
                // (ECR repositories philips-ecommerce/<module> must exist: aws ecr create-repository ...).
                withCredentials([usernamePassword(credentialsId: 'docker-registry-credentials',
                                                  usernameVariable: 'REGISTRY_USER',
                                                  passwordVariable: 'REGISTRY_PASSWORD')]) {
                    // Single quotes: the secret is expanded by the shell, never interpolated by Groovy
                    // (avoids leaking it into the build log / process list).
                    sh 'echo "$REGISTRY_PASSWORD" | docker login --username "$REGISTRY_USER" --password-stdin "$DOCKER_REGISTRY"'
                }
                script {
                    try {
                        for (m in MODULES) {
                            sh """
                               docker tag philips-ecommerce/${m}:${env.APP_TAG} \$DOCKER_REGISTRY/philips-ecommerce/${m}:${env.APP_TAG}
                               docker push \$DOCKER_REGISTRY/philips-ecommerce/${m}:${env.APP_TAG}
                            """
                        }
                    } finally {
                        sh 'docker logout "$DOCKER_REGISTRY" || true'
                    }
                }
            }
        }

        stage('Deploy to EC2') {
            when {
                allOf {
                    expression { params.DEPLOY_TO_EC2 }
                    expression { params.PUSH_IMAGES }   // the instance pulls from the registry
                }
            }
            steps {
                script {
                    if (!env.EC2_HOST?.trim()) { error 'EC2_HOST is not set' }
                }
                // 'ec2-prod-env' = Secret file credential holding the production .env
                // (template: deploy/ec2/env.prod.example). It is copied, never committed.
                withCredentials([file(credentialsId: 'ec2-prod-env', variable: 'PROD_ENV_FILE')]) {
                    sshagent(credentials: ['ec2-ssh-key']) {
                        sh '''
                            set -eu
                            TARGET="ec2-user@${EC2_HOST}"
                            APP_DIR=/opt/philips-ecommerce
                            SSH_OPTS="-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15"

                            # Files the instance needs (layout documented in deploy/docker-compose.prod.yml).
                            ssh $SSH_OPTS "$TARGET" "mkdir -p $APP_DIR && cd $APP_DIR && rm -rf config-repo docker"
                            scp $SSH_OPTS deploy/docker-compose.prod.yml deploy/ec2/deploy.sh "$TARGET:$APP_DIR/"
                            scp $SSH_OPTS "$PROD_ENV_FILE" "$TARGET:$APP_DIR/.env"
                            tar czf - config-repo docker/mysql/init docker/mysql/grant-app-user.sh docker/kafka/create-topics.sh \
                              | ssh $SSH_OPTS "$TARGET" "tar xzf - -C $APP_DIR"

                            # deploy.sh: sets IMAGE_TAG in .env, ECR login, docker compose pull && up -d,
                            # waits for health, prints a rollback hint on failure.
                            ssh $SSH_OPTS "$TARGET" "cd $APP_DIR && chmod 600 .env && chmod +x deploy.sh && ./deploy.sh $APP_TAG"
                        '''
                    }
                }
                // Smoke check from the Jenkins side through the public entry point (port 8080 must be
                // reachable from the agent - see deploy/aws/security-groups.md).
                sh '''
                    for i in $(seq 1 30); do
                      if curl -fsS --max-time 5 "http://${EC2_HOST}:8080/actuator/health" | grep -q '"status":"UP"'; then
                        echo "Gateway on ${EC2_HOST} is UP"; exit 0
                      fi
                      sleep 10
                    done
                    echo "Gateway on ${EC2_HOST} did not become healthy - rollback: ./deploy.sh <previous tag> (see .deploy-history)"
                    exit 1
                '''
            }
        }
    }

    post {
        always {
            archiveArtifacts allowEmptyArchive: true,
                             artifacts: '**/target/surefire-reports/*.xml, **/target/failsafe-reports/*.xml, **/target/site/jacoco/jacoco.xml'
            // Local images of this build are not needed after push; dangling layers are pruned.
            sh 'docker image prune -f > /dev/null 2>&1 || true'
            cleanWs()
        }
        success {
            echo "Pipeline OK - images tagged ${env.APP_TAG}"
        }
        failure {
            echo 'Pipeline FAILED - check the stage logs, test reports and the SonarQube dashboard.'
        }
    }
}
