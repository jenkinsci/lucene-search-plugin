# Soak harness for a live Jenkins

`lucene_stress.py` runs the plugin's real read and write paths against a running
Jenkins: several threads triggering builds (RunListener / SaveableListener write
to the index) while several threads search (`/search/suggest`). It is for
validating a build of the plugin on a production-like instance; the in-repo
`SearchBackendManagerStressTest` covers the same ground in CI, plus the failure
that cannot be driven from outside the JVM — a writer being closed under the
plugin.

## Requirements

- Python 3.8+, no third-party packages.
- A Jenkins user with Job/Create, Job/Build, Job/Delete and Job/Read (an API
  token works as the password). Administer is needed only for `--reconfigure`
  and `--restart-after`.
- A shell agent for the generated jobs (they run `echo $MARKER`). Trivially true
  on a Linux controller; point `--jobs` at a label-restricted setup at your own
  peril.

## Usage

```sh
# gentle 2-minute smoke: 4 search threads, 1 build every ~1 second
./lucene_stress.py --url https://jenkins.example.com --user me --token <token>

# real soak, and restart Jenkins in the middle of it
./lucene_stress.py --url ... --user me --token ... \
    --duration 900 --readers 6 --writers 2 --build-interval 0.5 \
    --restart-after 300

# with a configuration save (best effort, see below)
./lucene_stress.py --url ... --reconfigure --reconfigure-after 60
```

Credentials can come from `JENKINS_URL`, `JENKINS_USER`, `JENKINS_TOKEN`.

## What it does and checks

It creates `--jobs` jobs named `lucene-stress-<runid>-*`, triggers builds with a
unique `MARKER` parameter each, and afterwards searches every triggered marker:
**each one must come back from the index**. Exit code is 1 if any build is
missing, 0 otherwise. The jobs (and their builds) are deleted again unless you
pass `--keep-jobs` — that deletion also exercises the plugin's job-delete path.

At the end it prints read/write counts, error kinds, p50/p95 search latency, and
the builds it could not find in the index.

## The injections, and their limits

- `--restart-after SECONDS` — `POST /safeRestart`, wait for Jenkins to come
  back, keep soaking. **This restarts your Jenkins**; it is what exercises the
  shutdown path (writer committed and lock released before the JVM exits).
- `--reconfigure` — submits the global configuration, which makes the plugin
  close and rebuild its writer. The form shape is Jenkins-version dependent, so
  this is best-effort and changes nothing if the submit is rejected. The
  dependable version is to press **Save** in *Manage Jenkins → Configure
  System* while the soak runs.
- Killing the writer inside the JVM cannot be driven over HTTP. Use
  `SearchBackendManagerStressTest` (`-Dlucene.stress.seconds=300 mvn test
  -Dtest=SearchBackendManagerStressTest`) for that.

## Reading the result

Whatever the plugin did internally, the log tells you whether it healed. During
and after a run, check:

```sh
grep -E "Search backend died|rebuilding it and retrying|Search backend discarded|Replacing a search backend|is locked by another search backend|Closing the search backend|Failed to close the index writer" <jenkins log>
```

`Search backend died ... rebuilding it and retrying` followed by a successful
write is the plugin recovering by itself — the thing this harness exists to
provoke. A `is locked by another search backend in this Jenkins JVM` line that
never clears means a writer is leaked and a restart is required; please report
it with the log excerpt.
