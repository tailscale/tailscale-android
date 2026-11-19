def checks: [
  {check: "Android CI", path: ".github/workflows/android.yml", job: "build", slug: "android-ci"},
  {check: "Smoke Test", path: ".github/workflows/smoke-test.yml", job: "smoke-test", slug: "smoke-test"},
  {check: "Integration Test", path: ".github/workflows/integration-test.yml", job: "integration-test", slug: "integration-test"}
];

def esc: gsub("&"; "&amp;") | gsub("<"; "&lt;") | gsub(">"; "&gt;");
def code($tick): "`" + (gsub("`"; $tick) | esc) + "`";
def link($url; $label): if ($url // "") == "" then ($label | esc) else "<" + $url + "|" + ($label | esc) + ">" end;
def cap: if length > 5 then (.[:5] | join(" · ")) + " · and \(length - 5) more" else join(" · ") end;
def pretty: if test("/") then split("/")[1:] | join(" › ") | gsub("_"; " ") else . end;
def minutes:
  if (.started_at // "") != "" and (.completed_at // "") != ""
  then ((.completed_at | fromdateiso8601) - (.started_at | fromdateiso8601)) / 60 | round | tostring
  else "" end;
def stopped: [ .steps[]? | select(.conclusion == "failure" or .conclusion == "cancelled") | .name ][0] // "";

def status($c):
  if $c.result == "success" and ($c.report.retried // 0) > 0 then ["⚠️", "success after a retry"]
  elif $c.result == "success" then ["✅", "success"]
  elif $c.result == "failure" then ["❌", "failure"]
  elif $c.result == "cancelled" and $c.timed_out then ["⏱️", "timed out after \($c.minutes) min"]
  elif $c.result == "cancelled" then ["🚫", "cancelled"]
  elif $c.result == "skipped" then ["⏭️", "skipped"]
  elif $c.result == "running" then ["⏳", "still running"]
  elif $c.result == "missing" then ["❓", "didn't run"]
  elif $c.result == "unknown" then ["❓", "couldn't read from GitHub"]
  else ["❓", $c.result] end;

def entries($recs): [ .[] as $n | link(([ $recs[] | select(.name == $n) | .url ][0] // ""); $n | pretty) ];
def recorded($recs): any(.[]; . as $n | any($recs[]; .name == $n));
def cases($r):
  ($r.recordings // []) as $recs | ($r.failed_cases // []) as $fc | ($r.flaky_cases // []) as $kc
  | [ (if ($fc | length) > 0 then (if ($fc | recorded($recs)) then "🎥 Failed: " else "❌ Failed: " end) + ($fc | entries($recs) | cap) else empty end),
      (if ($kc | length) > 0 then (if ($kc | recorded($recs)) then "🎥 Failed attempt: " else "⚠️ Flaky: " end) + ($kc | entries($recs) | cap) else empty end),
      (if ($r.more_recordings // 0) > 0 and ($r.all_recordings_url // "") != "" then link($r.all_recordings_url; "+\($r.more_recordings) more recordings") else empty end)
    ] | join(" · ");

def ran($r): ($r.has_results // false);
def stopped_at($c): if ($c.stopped_step // "") != "" then "stopped at " + ($c.stopped_step | esc) else empty end;

def pct($p; $f): if $p + $f > 0 then " (\($p * 100 / ($p + $f) | floor)%)" else "" end;

def counts($r; $result):
  ($r.passed // 0) as $p | ($r.failed // 0) as $f | ($r.skipped // 0) as $s | ($r.retried // 0) as $t
  | if ran($r) | not then "No test results"
    else "\($p) passed" + (if $t > 0 then " (\($t) on retry)" else "" end) + " · \($f) failed · \($s) skipped" + pct($p; $f)
      + (if $p + $f == 0 then " · ⚠️ no tests executed"
         elif $result == "failure" and $f == 0 then " · ⚠️ results may be partial (build error, crash or timeout)"
         else "" end)
    end;

def short($r):
  ($r.passed // 0) as $p | ($r.failed // 0) as $f | ($r.skipped // 0) as $s | ($r.retried // 0) as $t
  | "\($p) passed" + (if $t > 0 then " (\($t) on retry)" else "" end) + " · \($f) failed"
    + (if $s > 0 then " · \($s) skipped" else "" end) + pct($p; $f) + (if $p + $f == 0 then " · ⚠️ none executed" else "" end);

def joblinks($c):
  [ (if ($c.job_url // "") != "" then link($c.job_url; "View Job Log") else empty end),
    (if ($c.log_url // "") != "" then link($c.log_url; "Download Job Log") else empty end) ] | join(" · ");

def detail($c):
  ($c.report // {}) as $r
  | if $c.slug == "android-ci" then
      (if $c.result != "success" and ($c.stopped_step // "") != "" then "failed at " + ($c.stopped_step | esc) else "" end)
    elif $c.result == "skipped" then "no relevant changes"
    elif ($c.result | IN("running", "missing", "unknown")) then ""
    elif ran($r) then short($r) + (cases($r) | if . == "" then "" else " · " + . end)
    elif ($c.result | IN("failure", "cancelled")) then [stopped_at($c), "no test results"] | join(", ")
    else "" end;

def line($c):
  status($c) as [$e, $w]
  | $e + " *" + ($c.check | esc) + ": " + $w + "*"
    + (detail($c) | if . == "" then "" else " · " + . end)
    + (if $c.result == "skipped" then "" else joblinks($c) | if . == "" then "" else " · " + . end end);

def where:
  .tick as $tick
  | if .event == "pull_request" then
      link(.pr_url; "PR #" + .pr_number) + ": "
      + (.pr_title | if length > 80 then .[:79] + "…" else . end | esc) + " · " + (.pr_branch | code($tick))
    elif .event == "schedule" then (.ref | code($tick)) + " · nightly"
    elif .event == "workflow_dispatch" then (.ref | code($tick)) + " · manual run"
    else (.ref | code($tick)) end;

def rerun:
  if .run_attempt != "1" then " · ↻ re-run of " + (.self.check | esc) + " (attempt " + .run_attempt + ")"
  elif .pr_action == "labeled" then " · ↻ " + (.self.check | esc) + " run requested by a label"
  else "" end;

def payload($text; $lines; $context):
  { text: $text,
    blocks: [ {type: "section", text: {type: "mrkdwn", text: ($lines | join("\n"))}},
              {type: "context", elements: [{type: "mrkdwn", text: $context}]} ] };

def single:
  . as $in | $in.self as $c | ($c.report // {}) as $r
  | status($c) as [$e, $w]
  | ($c.path | split("/") | last) as $file
  | ($in.repo_url + "/actions/workflows/" + $file + (if $in.event == "schedule" then "?query=event%3Aschedule" else "" end)) as $history
  | payload($e + " " + $c.check + ": " + $w;
      [ $e + " *" + ($c.check | esc) + ": " + $w + "* · " + ($in | where) + ($in | rerun),
        (if ran($r) then (cases($r) | select(. != ""))
         elif ($c.result | IN("failure", "cancelled")) and ($c.stopped_step // "") != "" then "⚠️ Stopped at " + ($c.stopped_step | esc)
         else empty end),
        (joblinks($c) | select(. != "") | "📄 " + .) ];
      (counts($r; $c.result) | esc) + (if $c.minutes != "" then " · \($c.minutes) min" else "" end)
        + " · " + link($history; (if $in.event == "schedule" then "All nightly " else "All " end) + $c.check + " runs"));

def combined:
  . as $in
  | [ checks[] as $k | (if $k.slug == $in.self.slug then $in.self else ($in.others[$k.slug] // {result: "missing"}) end) + {check: $k.check, slug: $k.slug} ] as $all
  | (if any($all[]; .result == "failure") then "❌"
     elif any($all[]; (.result | IN("success", "skipped")) | not) or any($all[]; (.report.retried // 0) > 0) then "⚠️"
     else "✅" end) as $overall
  | payload("PR #\($in.pr_number): " + ([ $all[] | .check + " " + (status(.)[1]) ] | join(" · "));
      [ $overall + " " + ($in | where) + ($in | rerun) ] + [ $all[] | line(.) ];
      "Commit " + ($in.sha[:7] | code($in.tick)) + " · " + link($in.pr_url + "/checks"; "All checks"));
