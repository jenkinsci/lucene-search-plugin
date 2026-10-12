#!/usr/bin/env python3
"""Soak the lucene-search plugin on a live Jenkins: readers against writers.

Triggers real builds (the plugin's write path: RunListener, SaveableListener)
while hammering the search endpoint (the read path), optionally forcing the
failures the plugin is supposed to survive. Nothing is written outside Jenkins;
the jobs it creates are removed again unless --keep-jobs is given.

    ./lucene_stress.py --url https://jenkins.example.com --user me --token <api-token>

Readers and writers both go through HTTP, so this exercises the concurrency the
plugin sees in production rather than the internals: several builds starting and
saving at once while users search. What it cannot do from outside is close a
writer in the JVM -- that needs the JUnit stress test
(SearchBackendManagerStressTest), which injects it directly.

Environment: JENKINS_URL, JENKINS_USER, JENKINS_TOKEN are used when the matching
option is not given.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from typing import Any

# Job config: a string parameter so every build carries a unique marker in its
# console log, which is what we later search for.
JOB_CONFIG = """<?xml version='1.1' encoding='UTF-8'?>
<project>
  <properties>
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions>
        <hudson.model.StringParameterDefinition>
          <name>MARKER</name>
          <description>lucene-search stress marker</description>
          <defaultValue>none</defaultValue>
        </hudson.model.StringParameterDefinition>
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
  </properties>
  <builders>
    <hudson.tasks.Shell>
      <command>echo $MARKER</command>
    </hudson.tasks.Shell>
  </builders>
</project>
"""

HEALING_LOG_LINES = (
    "Search backend died|rebuilding it and retrying|Search backend discarded|"
    "Replacing a search backend|is locked by another search backend|"
    "Closing the search backend|Failed to close the index writer"
)


class JenkinsError(RuntimeError):
    pass


@dataclass
class Stats:
    """Counters shared by every worker thread."""

    reads: int = 0
    read_errors: int = 0
    writes: int = 0
    write_errors: int = 0
    triggered: list[str] = field(default_factory=list)
    latencies: list[float] = field(default_factory=list)
    errors: dict[str, int] = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def read(self, seconds: float, error: str | None = None) -> None:
        with self.lock:
            self.reads += 1
            self.latencies.append(seconds)
            if error:
                self.read_errors += 1
                self.errors[error] = self.errors.get(error, 0) + 1

    def write(self, marker: str | None, error: str | None = None) -> None:
        with self.lock:
            self.writes += 1
            if marker:
                self.triggered.append(marker)
            if error:
                self.write_errors += 1
                self.errors[error] = self.errors.get(error, 0) + 1

    def percentile(self, fraction: float) -> float:
        if not self.latencies:
            return 0.0
        ordered = sorted(self.latencies)
        return ordered[min(len(ordered) - 1, int(len(ordered) * fraction))]


class Jenkins:
    """The handful of Jenkins HTTP calls this harness needs."""

    def __init__(self, url: str, user: str | None, token: str | None, timeout: float, insecure: bool) -> None:
        self.url = url.rstrip("/")
        self.timeout = timeout
        self.crumb_field: str | None = None
        self.crumb: str | None = None
        self.opener = urllib.request.build_opener()
        if insecure:
            import ssl

            context = ssl.create_default_context()
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            self.opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context))
        self.headers = {"User-Agent": "lucene-search-stress"}
        if user and token:
            token_bytes = base64.b64encode(f"{user}:{token}".encode()).decode()
            self.headers["Authorization"] = f"Basic {token_bytes}"

    def _request(self, method: str, path: str, data: bytes | None = None, content_type: str | None = None) -> bytes:
        request = urllib.request.Request(self.url + path, data=data, method=method, headers=dict(self.headers))
        if content_type:
            request.add_header("Content-Type", content_type)
        if self.crumb and self.crumb_field:
            request.add_header(self.crumb_field, self.crumb)
        with self.opener.open(request, timeout=self.timeout) as response:
            return response.read()

    def get(self, path: str) -> bytes:
        return self._request("GET", path)

    def post(self, path: str, data: bytes | None = None, content_type: str | None = None) -> bytes:
        return self._request("POST", path, data, content_type)

    def get_json(self, path: str) -> Any:
        return json.loads(self.get(path).decode("utf-8", "replace"))

    def post_json(self, path: str, payload: Any) -> Any:
        body = urllib.parse.urlencode({"json": json.dumps(payload)}).encode()
        return json.loads(self.post(path, body, "application/x-www-form-urlencoded").decode("utf-8", "replace"))

    def fetch_crumb(self) -> None:
        try:
            crumb = self.get_json("/crumbIssuer/api/json")
            self.crumb_field = crumb.get("crumbRequestField")
            self.crumb = crumb.get("crumb")
        except urllib.error.HTTPError as e:
            if e.code != 404:  # 404 = no crumb issuer, i.e. CSRF disabled
                raise

    def whoami(self) -> str:
        try:
            return self.get_json("/me/api/json?tree=id,fullName").get("id", "?")
        except Exception:
            return "anonymous"

    def create_job(self, name: str) -> None:
        self.post(f"/createItem?name={urllib.parse.quote(name)}", JOB_CONFIG.encode(), "application/xml")

    def delete_job(self, name: str) -> None:
        self.post(f"/job/{urllib.parse.quote(name)}/doDelete")

    def build(self, name: str, marker: str) -> None:
        query = urllib.parse.urlencode({"MARKER": marker, "delay": "0"})
        self.post(f"/job/{urllib.parse.quote(name)}/buildWithParameters?{query}")

    def queue_depth(self) -> int:
        return len(self.get_json("/queue/api/json?tree=items[id]").get("items", []))

    def scheduled_builds(self, names: list[str]) -> set[str]:
        """Markers of the builds this run triggered that are still running."""
        pending = set()
        for name in names:
            builds = self.get_json(
                f"/job/{urllib.parse.quote(name)}/api/json?tree=builds[number,building,actions[parameters[name,value]]]"
            ).get("builds", [])
            for build in builds:
                if not build.get("building"):
                    continue
                for action in build.get("actions", []):
                    for parameter in action.get("parameters", []):
                        if parameter.get("name") == "MARKER":
                            pending.add(parameter.get("value", ""))
        return pending

    def wait_until_quiet(self, names: list[str], timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                if self.queue_depth() == 0 and not self.scheduled_builds(names):
                    return True
            except Exception:
                pass
            time.sleep(2)
        return False

    def wait_until_up(self, timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                self.get("/api/json?tree=mode")
                self.fetch_crumb()
                return True
            except Exception:
                time.sleep(2)
        return False

    def search(self, query: str) -> list[str]:
        """The plugin's read path: suggestions for a query, as strings."""
        payload = self.get_json(f"/search/suggest?query={urllib.parse.quote(query)}")
        found = []
        for suggestion in payload.get("suggestions", []):
            if isinstance(suggestion, dict):
                found.extend(str(v) for v in suggestion.values() if isinstance(v, str))
            else:
                found.append(str(suggestion))
        return found


def read_worker(jenkins: Jenkins, stats: Stats, stop: threading.Event, queries: list[str]) -> None:
    index = 0
    while not stop.is_set():
        query = queries[index % len(queries)]
        index += 1
        started = time.monotonic()
        try:
            jenkins.search(query)
            stats.read(time.monotonic() - started)
        except Exception as e:
            stats.read(time.monotonic() - started, f"{type(e).__name__}")
        time.sleep(0.05)


def write_worker(
    jenkins: Jenkins, stats: Stats, stop: threading.Event, names: list[str], run_id: str, interval: float, max_queue: int
) -> None:
    index = 0
    while not stop.is_set():
        try:
            if jenkins.queue_depth() > max_queue:
                time.sleep(interval)
                continue
            name = names[index % len(names)]
            marker = f"LUCENE_STRESS_{run_id}_{index}"
            index += 1
            started = time.monotonic()
            try:
                jenkins.build(name, marker)
                stats.write(marker)
            except Exception as e:
                stats.write(None, f"{type(e).__name__}")
            elapsed = time.monotonic() - started
        except Exception as e:
            stats.write(None, f"{type(e).__name__}")
            elapsed = 0.0
        time.sleep(max(0.0, interval - elapsed))


def injector_worker(jenkins: Jenkins, stop: threading.Event, args: argparse.Namespace, names: list[str]) -> None:
    """The failure injections that can be driven from outside the JVM."""
    if args.restart_after:
        time.sleep(args.restart_after)
        if stop.is_set():
            return
        print(f"[inject] safeRestart in progress (was up as {jenkins.whoami()})", flush=True)
        try:
            jenkins.post("/safeRestart")
        except Exception as e:
            print(f"[inject] safeRestart request failed: {e}", flush=True)
            return
        if jenkins.wait_until_up(args.restart_timeout):
            print("[inject] Jenkins is back; resuming", flush=True)
        else:
            print(f"[inject] Jenkins did not come back within {args.restart_timeout:.0f}s", flush=True)
            stop.set()
    if args.reconfigure:
        time.sleep(args.reconfigure_after)
        if stop.is_set():
            return
        # Saving the global configuration runs SearchBackendConfiguration.configure()
        # -> reconfigure(), i.e. close the writer and build a new one.
        #
        # The payload is deliberately only the plugin's own (empty) section: the
        # plugin changes nothing it is not told about, and nothing here can move the
        # index path or flip useSecurity. Jenkins' binding of a partial form is
        # version dependent, so this is best-effort — if the submit is rejected
        # nothing changes. The dependable way is to press Save in
        # "Manage Jenkins -> Configure System" while the soak runs; either way,
        # check the log for "Closing the search backend".
        try:
            jenkins.post_json("/configSubmit", {"searchBackend": {}})
            print("[inject] global configuration submitted (confirm 'Closing the search backend' in the log)", flush=True)
        except Exception as e:
            print(f"[inject] configSubmit failed ({e}); press Save in Manage Jenkins instead", flush=True)


def verify(jenkins: Jenkins, markers: list[str]) -> tuple[int, int]:
    """Every build we triggered must be findable in the index afterwards.

    Each build echoed a marker unique to it, so searching that marker has to
    return its build. This is the end-to-end check: whatever the plugin did
    internally during the soak, nothing may be silently missing from the index.
    """
    missing = 0
    checked = 0
    for marker in markers:
        checked += 1
        try:
            found = jenkins.search(marker)
        except Exception as e:
            print(f"  ! search failed for {marker}: {e}")
            missing += 1
            continue
        if not found:
            missing += 1
            print(f"  ! {marker} is not in the index")
    return checked, missing


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--url", default=os.environ.get("JENKINS_URL"), help="Jenkins base URL (env JENKINS_URL)")
    parser.add_argument("--user", default=os.environ.get("JENKINS_USER"), help="user (env JENKINS_USER)")
    parser.add_argument("--token", default=os.environ.get("JENKINS_TOKEN"), help="API token (env JENKINS_TOKEN)")
    parser.add_argument("--jobs", type=int, default=2, help="jobs to create and build (default 2)")
    parser.add_argument("--readers", type=int, default=4, help="search threads (default 4)")
    parser.add_argument("--writers", type=int, default=1, help="build-triggering threads (default 1)")
    parser.add_argument("--duration", type=float, default=120.0, help="seconds to soak (default 120)")
    parser.add_argument("--build-interval", type=float, default=1.0, help="seconds between triggers per writer")
    parser.add_argument("--max-queue", type=int, default=20, help="pause triggering above this queue depth")
    parser.add_argument("--timeout", type=float, default=60.0, help="HTTP timeout in seconds")
    parser.add_argument("--quiet-timeout", type=float, default=600.0, help="wait for builds to finish")
    parser.add_argument("--insecure", action="store_true", help="do not verify TLS certificates")
    parser.add_argument("--keep-jobs", action="store_true", help="keep the created jobs and their builds")
    parser.add_argument(
        "--restart-after",
        type=float,
        default=0.0,
        help="seconds into the soak, restart Jenkins (/safeRestart) and continue after it is back",
    )
    parser.add_argument("--restart-timeout", type=float, default=600.0, help="how long to wait for Jenkins to return")
    parser.add_argument(
        "--reconfigure",
        action="store_true",
        help="also submit the global config mid-soak (best-effort; see the docstring)",
    )
    parser.add_argument("--reconfigure-after", type=float, default=30.0, help="when to submit it")
    args = parser.parse_args()

    if not args.url:
        parser.error("--url (or JENKINS_URL) is required")

    jenkins = Jenkins(args.url, args.user, args.token, args.timeout, args.insecure)
    try:
        jenkins.fetch_crumb()
        who = jenkins.whoami()
    except Exception as e:
        print(f"cannot reach {args.url}: {e}", file=sys.stderr)
        return 2

    run_id = uuid.uuid4().hex[:8]
    names = [f"lucene-stress-{run_id}-{i}" for i in range(args.jobs)]
    print(f"Jenkins {args.url} as {who}; run {run_id}")
    print(f"jobs: {', '.join(names)} ({args.jobs})  readers: {args.readers}  writers: {args.writers}  "
          f"duration: {args.duration:.0f}s")

    created: list[str] = []
    try:
        for name in names:
            jenkins.create_job(name)
            created.append(name)
    except Exception as e:
        print(f"cannot create jobs ({e}); does the user have Job/Create?", file=sys.stderr)
        for name in created:
            _quiet(lambda: jenkins.delete_job(name))
        return 2

    stop = threading.Event()
    stats = Stats()
    threads = [threading.Thread(target=read_worker, args=(jenkins, stats, stop, names), daemon=True)
               for _ in range(args.readers)]
    threads += [threading.Thread(
        target=write_worker,
        args=(jenkins, stats, stop, names, run_id, args.build_interval, args.max_queue),
        daemon=True,
    ) for _ in range(args.writers)]
    if args.restart_after or args.reconfigure:
        threads.append(threading.Thread(target=injector_worker, args=(jenkins, stop, args, names), daemon=True))

    started = time.monotonic()
    for thread in threads:
        thread.start()

    try:
        while time.monotonic() - started < args.duration and not stop.is_set():
            time.sleep(1)
            elapsed = time.monotonic() - started
            print(
                f"  {elapsed:6.0f}s  reads {stats.reads} ({stats.read_errors} err, "
                f"p95 {stats.percentile(0.95) * 1000:.0f} ms)  builds triggered {stats.writes} "
                f"({stats.write_errors} err)",
                flush=True,
            )
    except KeyboardInterrupt:
        print("\ninterrupted; verifying what ran so far", flush=True)
    stop.set()
    for thread in threads:
        thread.join(timeout=30)

    print("\nwaiting for the triggered builds to finish...")
    quiet = jenkins.wait_until_quiet(names, args.quiet_timeout)
    if not quiet:
        print("  ! builds are still running; results below may be incomplete")

    markers = sorted(set(stats.triggered))
    print(f"\nindexed? checking {len(markers)} triggered build(s)")
    checked, missing = verify(jenkins, markers)
    print(f"  {checked - missing}/{checked} builds found in the index")

    print("\nsummary")
    print(f"  reads        {stats.reads} ({stats.read_errors} errors), "
          f"p50 {stats.percentile(0.5) * 1000:.0f} ms, p95 {stats.percentile(0.95) * 1000:.0f} ms")
    print(f"  builds       {stats.writes} triggered ({stats.write_errors} errors)")
    if stats.errors:
        print(f"  error kinds  {stats.errors}")
    print(f"  indexed      {checked - missing}/{checked}")
    if not quiet:
        print("  note         builds were still running; re-check the index when they finish")
    print(f"\n  healing lines in the Jenkins log:\n    grep -E \"{HEALING_LOG_LINES}\" <jenkins log>")

    if args.keep_jobs:
        print(f"\nkept jobs (delete when done): {', '.join(names)}")
    else:
        print("\nremoving the created jobs...")
        for name in names:
            _quiet(lambda: jenkins.delete_job(name))
        print("  done")

    return 1 if missing else 0


def _quiet(action) -> None:
    try:
        action()
    except Exception as e:
        print(f"  ! {e}")


if __name__ == "__main__":
    sys.exit(main())
