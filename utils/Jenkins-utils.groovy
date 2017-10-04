#!groovy

/* 
 * Required parameters in your job:
 * - params.AWS_CREDENTIAL_ID="aws-credential-development"
 * - params.AWS_REGION="us-east-1"
 * - params.AWS_ECR_REPO="https://244603078690.dkr.ecr.us-east-1.amazonaws.com"
 * - params.AWS_ECR_REPO_CREDENTIAL_ID="ecr:us-east-1:aws-ecr-credential-development"
 */


/*
 * Login to AWS using the aws cli.
 *
 * Expects an AWS credentials created.
 */
def awsLogin() {
  if (!env.AWS_LOGIN_STOP) {
    withCredentials([[
      $class: "AmazonWebServicesCredentialsBinding",
      credentialsId: params.AWS_CREDENTIAL_ID,
      accessKeyVariable: "AWS_ACCESS_KEY_ID",
      secretKeyVariable: "AWS_SECRET_ACCESS_KEY"
    ]]) {
      sh "aws configure set aws_access_key_id ${env.AWS_ACCESS_KEY_ID}"
      sh "aws configure set aws_secret_access_key ${env.AWS_SECRET_ACCESS_KEY}"
      sh "aws configure set default.region ${params.AWS_REGION}"

      env.AWS_LOGIN_STOP = true
    }
  }
}

/*
 * Login to AWS ECR service using the aws cli.
 */
def awsEcrLogin() {
  awsLogin()
  
  if (!env.AWS_ECR_LOGIN_STOP) {
    sh "\$(aws ecr get-login --region ${params.AWS_REGION} --no-include-email)"
    env.AWS_ECR_LOGIN_STOP = true
  }
}

/*
 * Create AWS ecr repository
 */
def createEcrRepository(name = "myRepo") {
  echo "Creating ECR repository ${imageName}"
  docker.withRegistry(params.AWS_ECR_REPO, params.AWS_ECR_REPO_CREDENTIAL_ID) {
    awsECRLogin();
    
    try {
      sh "aws ecr describe-repositories --repository-names ${name}"
    } catch(err) {
      sh "aws ecr create-repository --repository-name ${name}"
    }
  }
}

/*
 * Clear docker temporary data
 */
def dockerGarbageCollect(appName) {
  echo "Cleaning docker temporary data"
  sh "docker rmi \$(docker images -f 'label=app=$appName' -a -q) --force || true"
  sh "docker rmi \$(docker images -f 'label=tag=$appName' -a -q) --force || true"
  sh "docker system prune -a -f || true"
}

/*
 * Get the latest version of the AWS ECR
 * 
 * => If your repository contains an image with the following tags: "v1.0.0" "607f8d4" "latest", this method will return "v1.0.0"
 */
String getLatestTagFromAwsEcrRepo() {
  awsEcrLogin()
  
  def shScript = " \
    ecrVersions=\$( \
      aws --region ${params.AWS_REGION} \
        ecr describe-images \
        --repository-name ${params.AWS_ECR_REPO} \
        --image-ids \
        imageTag=latest \
        --output text \
    ) && \
    version=\$( \
      echo \$ecrVersions | awk '{for (i=1;i<=NF;++i) {if(\$i~/^[v]/){print \$i}}}' \
    ) && \
    echo \$version"

  return sh(returnStdout: true, script: shScript).trim()
}

/*
 * Set revision version with timestamp
 */
String generateDockerImageVersion(version = "1.0.0") {
  def major = version.tokenize(".")[0]
  def minor = version.tokenize(".")[1]
  def newVersion = major + "." + minor + "." + env.TIMESTAMP

  return newVersion
}

/*
 * Use to set kubernetes control context
 */
def setKubeControlContext(context = "development", namespace = "development") {
  sh "kubectl config use-context ${context} --namespace=${namespace}"
}

/*
 * Build and push docker image to AWS ECR repo
 *
 * Structure:
 *    project_root
 *      /myApp
 *        k8s/
 *          development/
 *            ns.yaml
 *            cm.yaml
 *          staging/
 *            ns.yaml
 *            cm.yaml *          
 *          deploy.yml
 *          svc.yml
 *        src/
 *        Dockerfile
 *        .dockerignore
 */
def buildAndPushImageToEcr(appName, appVersion = "1.0.0") {
  createEcrRepository("${appName}")

  echo "Pushing ${appName}:${appVersion} Image to ECR"

  docker.withRegistry(params.AWS_ECR_REPO, params.AWS_ECR_REPO_CREDENTIAL_ID) {
    awsECRLogin();

    dir("${appName}") {
      def app = docker.build(
        "${appName}",
        "--rm --build-arg APP_NAME=${appName} --build-arg APP_VERSION=${appVersion} . "
      )
      app.push appVersion
      app.push "latest"
    }

    env.APP_VERSION = appVersion
  }

  dockerGarbageCollect("${appName}")
}

/*
 * 
 */
def deploy(namespace, appName, appVersion = "latest") {
  dir("${appName}/k8s/$namespace/") {
    sh "kubectl apply -f namespace.yaml"
    sh "kubectl apply --namespace=$namespace -f ."
  }

  if (appVersion == "latest") {
    appVersion = getLatestTagFromAwsEcrRepo("${appName}")
  }

  env.APP_VERSION = appVersion

  dir("${appName}/k8s/") {
    echo "Deploy ${appName}:${appVersion}"

    sh "sed -i 's/image: .*${appName}/&:${appVersion}/g' deploy.yaml"
    sh "kubectl apply --namespace=$namespace -f ."
  }
}
