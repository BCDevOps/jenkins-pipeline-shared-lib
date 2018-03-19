# Set GIT Repo URL and Branch name in buildconfig 

This example set the Repo URL to a forked repo and defines a pull request as the branch to build from:
```
oc -n agehlers-sandbox patch bc/gitbook -p '{"spec":{"source":{"git":{"ref": "refs/pull/2/head", "uri": "https://github.com/agehlers/tested.git"}}}}';
```

