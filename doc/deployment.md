# Deployment

## Backend

```
$ lein ring uberwar
```

Navigate to http://46.101.125.179:8080/manager/html

Undeploy "/instant-website-0.1.0-SNAPSHOT-standalone"

Select new WAR under "WAR file to deploy" and click "Deploy"

Deployment now live at https://api.instantwebsite.app

### Uberjar

```
$ lein uberjar
$ scp target/uberjar/instant-website-0.1.0-SNAPSHOT-standalone.jar root@46.101.125.179:/instant-website/instant-website.jar
$ ssh root@46.101.125.179 journalctl -f
$ ssh root@46.101.125.179 systemctl restart instant-website
```

#### Remote REPL

```
$ ssh -L 47326:localhost:47326 root@46.101.125.179
```


### Future

Each new deploy should increment the version.

Undeploy should not be used until after deployment is confirmed working.

After deploying new version, rotate version in caddy and keep old version still
online.

## Frontend

```
$ cd frontend
$ npx shadow-cljs release frontend
$ rsync -avzP ./public/ root@46.101.125.179:/instantwebsite-frontend
```

Deployment now live at https://dashboard.instantwebsite.app
