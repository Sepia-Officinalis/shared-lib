package release

class NightlyHelpers implements Serializable {

    def steps
    def builtImages = [:]
    def commitMap = [:]

    NightlyHelpers(steps) {
        this.steps = steps
    }

    // ---------------------------------------------------------
    // CHECKOUT LOGIC
    // ---------------------------------------------------------

    /**
        * Checkout all repos on their dev branches
        * @param repositories - list of repository names
        * @param branch - branch name to checkout (default should be dev)
        * @param bitbucketUrl - Bitbucket server URL
        * @param bitbucketProject - Bitbucket project key
        * @param credentialsId - Jenkins credentials ID for Bitbucket
        * @return commitMap - map of repository names to their checked out commit hashes
    */
    def checkoutAllDevBranches(List repositories, String branch, String bitbucketUrl, String bitbucketProject, String credentialsId) {
        steps.echo "Checking out ${branch} branch for ${repositories.size()} repos..."

        repositories.each { repo -> 
            steps.dir(repo) {
                steps.checkout([
                    $class: 'GitSCM',
                    branches: [[name: "*/${branch}"]],
                    extensions: [
                        [$class: 'CleanBeforeCheckout'],
                        [$class: 'CloneOption', depth: 0, noTags: false, shallow: false ] // make sure this works
                    ]
                    userRemoteConfigs: [[
                        url: "${bitbucketUrl}/scm/${bitbucketProject}/${repo}.git",
                        credentialsId: credentialsId
                    ]]
                ])
                // commit hash
                def commitSha = steps.sh(
                    script: "git rev-parse HEAD",
                    returnStdout: true
                ).trim()

                commitMap[repo] = commitSha
                steps.echo "Checked out ${repo} at commit ${commitSha}"
            }
        }
        return commitMap
    }


    // ---------------------------------------------------------
    // DOCKER IMAGE BUILD
    // ---------------------------------------------------------

    /**
        * Build Docker images for all repos w specified tag
        * @param repositories - list of repository names
        * @param tag - Docker image tag
        * @param registry - Docker registry URL
        * @param buildNumber - Jenkins build number for labeling
        * @return Map of repo -> full image name
    */
    def buildAllDockerImages(List repositories, String tag, String registry, String buildNumber) {
        steps.echo "Building images with tag ${tag}"

        repositories.each { repo -> 
            steps.dir(repo) {
                def imageName = "${registry}/${repo}:${tag}"
                def commitSha = commitMap[repo] ?: 'unknown'

                steps.echo "Building image ${imageName}..."

                steps.sh """
                    docker build \
                        --label "build.number=${buildNumber}" \
                        --label "git.commit=${commitSha}" \
                        -t ${imageName} \
                        -t {registry}/${repo}:latest \
                        .
                """
                builtImages[repo] = imageName
                steps.echo "Built: ${imageName}"
            }
        }
        return builtImages
    }

    /**
    * Push all built images to registry
    * @param images Map of repo -> full image name
    */
    def pushAllDockerImages(Map images = null) {
        def targetImages = images ?: builtImages
        steps.echo "Pushing ${targetImages.size()} images to registry..."

        targetImages.each { repo, imageName -> 
            steps.sh "docker push ${imageName}..."
            steps.sh "docker push ${imageName.replaceAll(':.*', ':latest')}"
            steps.echo "Pushed: ${imageName}"
        }
    }

    /**
    * Tag images with new tag and push
    * @param sourceTag - original tag
    * @param targetTag - new tag to apply
    */
    def retagAndPushImages(String sourceTag, String targetTag, String registry) {
        steps.echo "Retagging images from ${sourceTag} to ${targetTag}..."

        builtImages.each { repo, sourceImage -> 
            def targetImage = "${registry}/${repo}:${targetTag}"

            steps.sh """
                docker pull ${sourceImage}
                docker tag ${sourceImage} ${targetImage}
                docker push ${targetImage}
            """
            steps.echo "Retagged and pushed: ${targetImage}"
        }
    }

    // ---------------------------------------------------------
    // DEPLOYMENT LOGIC
    // ---------------------------------------------------------

    /**
        * Deploy minimal test stack using docker-compose
        * @param deploymentId - unique deployment identifier
        * @param composeFile - path to docker-compose file
        * @param waitSeconds - time to wait for services to initialize
    */
    def deployMinimalStack(String deploymentId, String composeFile = 'docker-compose.nightly.yml', int waitSeconds = 30) {
        steps.echo "Deploying minimal stack with deployment ID: ${deploymentId}..."

        def envContent = "DEPLOYMENT_ID=${deploymentId}\n"
        builtImages.each { repo, imageName -> 
            def envVar = repo.toUpperCase().replaceAll(/[^A-Z0-9]/, '_') + '_IMAGE'
            envContent += "${envVar}=${imageName}\n"
        }

        steps.writeFile(file: '.env.nightly', text: envContent)


        steps.sh """
            # source env
            set -a
            source .env.nightly
            set +a
            # pull images
            docker-compose -f ${composeFile} pull || true
            # deploy stack
            docker-compose -f ${composeFile} -p ${deploymentId} up -d --force-recreate
            # wait for services to initialize
            echo "Waiting ${waitSeconds} seconds for services to initialize..."
            sleep ${waitSeconds}
            # verify
            docker-compose -f ${composeFile} -p ${deploymentId} ps
        """
        steps.echo "Deployment ${deploymentId} complete."
    }

    /** 
        * Verify health of deployed services
        * @param deploymentId - unique deployment identifier
        * @param healthCheckUrl
        * @param retries - number of retries for health check
    */
    def verifyDeploymentHealth(String deploymentId, String healthCheckUrl = null, int retries = 5) {
        steps.echo "verifying deployment health for ${deploymentId}..."
        if (healthCheckUrl) {
            steps.retry(retries) {
                steps.sh "curl -f ${healthCheckUrl} || exit 1"
            }
        }
        // checking container status
        def result = steps.sh(
            script: "docker ps --filter 'label=com.docker.compose.project=${deploymentId}' --format '{{.Status}}' | grep -v 'Up' | wc -1",
            returnStdout: true
        ).trim()

        if (result !- '0') {
            steps.error("Some containers are not healthy in deployment ${deploymentId}")
        }
        steps.echo "Deployment ${deploymentId} is healthy."
    }

/**
    * Full cleanup of deployment
    * @param deploymentId - unique deployment identifier
    * @param composeFile - path to docker-compose file
    */
    def tearDownStack(String deploymentId, String composeFile = 'docker-compose.nightly.yml') {
        steps.echo "Tearing down deployment ${deploymentId}..."

        steps.sh """
            docker-compose -f ${composeFile} -p ${deploymentId} down --volumes --remove-orphans || true
        """
        steps.echo "Teardown of deployment ${deploymentId} complete."
    }


    // ---------------------------------------------------------
    // TEST EXECUTION
    // ---------------------------------------------------------