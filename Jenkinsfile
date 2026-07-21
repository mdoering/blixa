// CD for Blixa (ColDP editor) -> GBIF dev/prod. Place at the root of the `blixa` repo.
// Backend builds with the agent's native JDK 25 + Maven; only npm runs in Docker (node:24),
// matching the col-checklistbank job. Deploy = rsync over ssh as jenkins-deploy + service restart.
pipeline {
  agent any

  parameters {
    choice(name: 'TARGET', choices: ['dev', 'prod'],
           description: 'Deploy environment (dev = apps.dev.checklistbank.org)')
  }

  options { timestamps(); disableConcurrentBuilds() }

  // Managed tool names as registered in this Jenkins (same as the other CoL Java jobs).
  // Puts mvn on PATH and pins the build to JDK 25 (Blixa does not compile on 21).
  tools {
    maven 'Maven 3.9.9'
    jdk 'LibericaJDK25'
  }

  environment {
    DEPLOY_USER = 'jenkins-deploy'
    APP_DOCROOT = '/var/www/html/blixa'
  }

  stages {
    stage('Build') {
      parallel {
        stage('Backend jar') {
          steps {
            // Native JDK 25 + Maven on the agent (no Docker); reuses the agent's ~/.m2 cache.
            sh 'cd backend && mvn -B -DskipTests clean package'
          }
        }
        stage('Frontend SPA') {
          steps {
            // npm in Docker, like the col-checklistbank job. Do NOT set NODE_ENV=production:
            // that makes `npm ci` skip devDependencies (typescript/vite), and the build needs
            // `tsc`/`vite`. `vite build` still emits a production bundle regardless of NODE_ENV.
            sh '''
              docker run --rm --user "$(id -u)" \
                -v "$PWD":/usr/src/app -w /usr/src/app/frontend \
                -e npm_config_cache=/usr/src/app/frontend/.npm \
                node:24 sh -c "npm ci && npm run build"
            '''
          }
        }
      }
    }

    stage('Deploy') {
      // No branch guard: the job's SCM builds only main. NB `when { branch 'main' }` NEVER matches
      // in a single-branch Pipeline job (BRANCH_NAME is set only for Multibranch), which silently
      // skips the whole stage. No sshagent/credential either — the Jenkins user's own SSH key is
      // authorized as jenkins-deploy on the VM (same setup col-checklistbank relies on).
      steps {
        sh '''
          set -eu
          case "$TARGET" in
            dev)  HOST=apps.dev.checklistbank.org ;;
            prod) HOST=apps.checklistbank.org ;;
            *)    echo "Unknown TARGET: $TARGET"; exit 1 ;;
          esac
          SSHOPT="-o StrictHostKeyChecking=accept-new"
          SSH="ssh $SSHOPT ${DEPLOY_USER}@${HOST}"

          # --- Backend first, so a frontend/docroot issue can't block the service update ---
          echo "== Backend jar -> staging =="
          rsync -t -e "ssh $SSHOPT" \
            backend/target/blixa-backend-*.jar \
            ${DEPLOY_USER}@${HOST}:/tmp/blixa-backend.jar

          echo "== Install jar as col + restart service =="
          # jenkins-deploy has `(col) NOPASSWD: ALL`, so install the jar as the col user;
          # the systemctl restart is granted separately (jenkins-deploy-col-blixa sudoers).
          $SSH 'sudo -u col install -m 644 /tmp/blixa-backend.jar /home/col/bin/blixa/blixa-backend.jar && rm -f /tmp/blixa-backend.jar'

          echo "== Refresh deploy config from the VM's deploy-repo checkout =="
          # The systemd EnvironmentFile (blixa/blixa-dev.env) and the Apache vhost live in a git
          # checkout at /home/col/bin on the VM, NOT in this repo -- so a jar-only deploy leaves them
          # stale and a config change (e.g. DB_URL) never lands, silently crash-looping the service.
          # Pull the checkout as col, then refuse to restart if DB_URL isn't set.
          $SSH 'sudo -u col git -C /home/col/bin pull --ff-only'
          $SSH 'grep -q "^DB_URL=" /home/col/bin/blixa/blixa-dev.env || { echo "ERROR: DB_URL missing in blixa-dev.env -- refusing to restart"; exit 1; }'

          $SSH sudo /usr/bin/systemctl restart col-blixa.service

          echo "== Backend health check =="
          # Standalone command (no `&& echo`): a trailing `&& echo` would make curl a non-final
          # part of an && list, which `set -e` exempts — silently masking a failed health check.
          $SSH 'curl -fsS --retry 10 --retry-delay 3 --retry-connrefused \
                  http://127.0.0.1:8111/api/ping'
          echo "  backend healthy"

          # --- Frontend ---
          echo "== Frontend -> ${HOST}:${APP_DOCROOT} =="
          rsync -rt --delete -e "ssh $SSHOPT" \
            frontend/dist/ ${DEPLOY_USER}@${HOST}:${APP_DOCROOT}/
        '''
      }
    }
  }

  post {
    success { echo "Deployed to ${params.TARGET}" }
    failure { echo "Build/deploy failed for ${params.TARGET}" }
  }
}
