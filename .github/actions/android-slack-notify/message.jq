def checks: [
  {check: "Android CI", path: ".github/workflows/android.yml", job: "build", slug: "android-ci"},
  {check: "Smoke Test", path: ".github/workflows/smoke-test.yml", job: "smoke-test", slug: "smoke-test", nightly: "posts"},
  {check: "Integration Test", path: ".github/workflows/integration-test.yml", job: "integration-test", slug: "integration-test", nightly: "joins"}
];

def esc: gsub("&"; "&amp;") | gsub("<"; "&lt;") | gsub(">"; "&gt;");
def code($tick): "`" + (gsub("`"; $tick) | esc) + "`";
def link($url; $label): if ($url // "") == "" then ($label | esc) else "<" + $url + "|" + ($label | esc) + ">" end;
def cap: if length > 5 then (.[:5] | join(", ")) + ", and \(length - 5) more" else join(", ") end;
def pretty: if test("/") then split("/")[1:] | join(" › ") | gsub("_"; " ") else . end;

def state($c):
  if $c.result == "success" then ["✅", "success"]
  elif $c.result == "failure" then ["❌", "failure"]
  elif $c.result == "cancelled" and $c.timed_out then ["⏱️", "timed out after \($c.minutes) min"]
  elif $c.result == "cancelled" then ["🚫", "cancelled"]
  elif $c.result == "skipped" then ["⏩", "skipped"]
  elif $c.result == "running" then ["⏳", "still running"]
  elif $c.result == "missing" then ["❓", "didn't run"]
  elif $c.result == "unknown" then ["❓", "couldn't read from GitHub"]
  else ["❓", $c.result] end;

def color($result): if $result == "success" then "good" elif $result == "failure" then "danger" else "warning" end;

def ran($r): ($r.has_results // false);

def counts($r):
  ($r.passed // 0) as $p | ($r.failed // 0) as $f | ($r.skipped // 0) as $s
  | "\($p) passed, \($f) failed, \($s) skipped" + (if $p + $f > 0 then " (\($p * 100 / ($p + $f) | floor)%)" else "" end);

def caveat($c):
  ($c.report // {}) as $r
  | if $c.result == "cancelled" then " - " + state($c)[1]
    elif ($r.passed // 0) + ($r.failed // 0) == 0 then " - no tests executed"
    elif $c.result == "failure" and ($r.failed // 0) == 0 then " - results may be partial (build error, crash or timeout)"
    else "" end;

def emoji($c):
  ($c.report // {}) as $r
  | if ran($r) and (($r.failed // 0) > 0 or $c.result == "failure") then "❌"
    elif ran($r) and $c.result == "success" and caveat($c) != "" then "⚠️"
    else state($c)[0] end;

def detail($c):
  ($c.report // {}) as $r | state($c)[1] as $w
  | if ran($r) then counts($r) + caveat($c)
    elif $c.slug != "android-ci" and $c.result == "skipped" then $w + " - no relevant changes"
    elif ($c.result | IN("failure", "cancelled")) and ($c.stopped_step // "") != "" then $w + " - stopped at " + ($c.stopped_step | esc)
    else $w end;

def names($recs):
  [ .[] as $n | ([ $recs[] | select(.name == $n) | .url ][0] // "") as $u
    | if $u == "" then $n | pretty | esc else "🎥 " + link($u; $n | pretty) end ] | cap;

def subs($c):
  ($c.report // {}) as $r | ($r.recordings // []) as $recs
  | ($r.failed_cases // []) as $fc | ($r.flaky_cases // []) as $kc
  | [ (if ($fc | length) > 0 then "❌ Failed Tests: " + ($fc | names($recs)) else empty end),
      (if ($kc | length) > 0 then "⚠️ Flaky Tests (\($kc | length) passed on retry): " + ($kc | names($recs)) else empty end),
      (if ($c.result | IN("failure", "cancelled")) or ($fc | length) > 0 or ($kc | length) > 0 then
         [ (if ($c.job_url // "") != "" then link($c.job_url; "View Job Log") else empty end),
           (if ($c.log_url // "") != "" then link($c.log_url; "Download Job Log") else empty end),
           (if ($r.more_recordings // 0) > 0 and ($r.all_recordings_url // "") != ""
            then link($r.all_recordings_url; "+\($r.more_recordings) more recordings") else empty end)
         ] | join(" · ") | select(. != "")
       else empty end)
    ] | map("    ↳ " + .);

def line($c): [ emoji($c) + " " + ($c.check | esc) + ": " + detail($c) ] + subs($c);

def heading($mode):
  if .event == "pull_request" then .pr_title | if length > 80 then .[:79] + "…" else . end
  elif .event == "schedule" then "Android Nightly " + (if $mode == "combined" then "Tests" else .self.check end)
  else "Android " + .self.check end;

def context:
  .tick as $tick
  | if .event == "pull_request" then
      link(.pr_url; "PR #" + .pr_number) + " · " + (.pr_branch | code($tick))
      + (if .run_attempt != "1" then " · 🔄 re-run of " + (.self.check | esc) + " (attempt " + .run_attempt + ")"
         elif .pr_action == "labeled" then " · " + (.self.check | esc) + " run requested by a label"
         else "" end)
    else "*" + (.repo_url | split("/") | .[-2:] | join("/") | esc) + "@" + (.ref | esc) + "*"
      + (if .run_attempt != "1" then " · 🔄 re-run (attempt " + .run_attempt + ")" else "" end)
    end;

def overall($all):
  if any($all[]; .result == "failure") then "failure"
  elif all($all[]; .result | IN("success", "skipped")) then "success"
  else "incomplete" end;

def message($mode):
  . as $in
  | [ checks[] | select(.nightly or $in.event != "schedule") as $k | (if $k.slug == $in.self.slug then $in.self else ($in.others[$k.slug] // {result: "missing"}) end) + {check: $k.check, slug: $k.slug} ] as $all
  | (if $mode == "combined" then overall($all) else $in.self.result end) as $result
  | (if $mode == "combined" then ({success: ["✅", "success"], failure: ["❌", "failure"]}[$result] // ["⚠️", "incomplete"]) else state($in.self) end) as [$e, $w]
  | (if $mode == "combined" and $in.event == "pull_request" then $in.pr_url + "/checks" else $in.repo_url + "/actions/runs/" + $in.run_id end) as $url
  | ($e + " " + ($in | heading($mode)) + ": " + $w | esc) as $title
  | {attachments: [{
      fallback: $title,
      color: color($result),
      title: $title,
      title_link: $url,
      text: ([ ($in | context), "" ] + [ (if $mode == "single" then [$in.self] else $all end)[] | line(.)[] ] + [ "", link($url; "View run details") ] | join("\n")),
      mrkdwn_in: ["text"]
    }]};
