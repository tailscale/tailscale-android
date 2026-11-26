const fs = require("fs");

async function checks(exec) {
  const { stdout } = await exec.getExecOutput("jq", ["-n", "-c", "-L", __dirname, 'include "message"; checks'], {
    silent: true,
  });
  return JSON.parse(stdout);
}

async function jobs(github, context, runId, attempt) {
  const { data } = await github.rest.actions.listJobsForWorkflowRunAttempt({
    ...context.repo,
    run_id: runId,
    attempt_number: attempt,
    per_page: 100,
  });
  return data.jobs || [];
}

function minutes(job) {
  if (!job.started_at || !job.completed_at) {
    return "";
  }
  return String(Math.round((Date.parse(job.completed_at) - Date.parse(job.started_at)) / 60000));
}

function stopped(job) {
  let step = (job.steps || []).find(({ conclusion }) => conclusion === "failure" || conclusion === "cancelled");
  // A runner lost mid-step leaves that step unfinished, so that's where the job stopped.
  step = step || (job.steps || []).find(({ conclusion }) => !conclusion);
  return step ? step.name : "";
}

async function createdAt(github, context) {
  const { data } = await github.rest.actions.getWorkflowRun({ ...context.repo, run_id: process.env.GITHUB_RUN_ID });
  return data.created_at;
}

// A nightly slot's two runs start within 3 minutes of each other, and slots are 20 minutes apart.
async function scheduledRun(github, context, path, at) {
  const { data } = await github.rest.actions.listWorkflowRunsForRepo({
    ...context.repo,
    event: "schedule",
    per_page: 100,
  });
  const gap = (r) => Math.abs(Date.parse(r.created_at) - Date.parse(at));
  return (data.workflow_runs || [])
    .filter((r) => r.path === path && gap(r) <= 10 * 60000)
    .reduce((best, r) => (best && gap(best) <= gap(r) ? best : r), undefined);
}

function readJSON(path) {
  return fs.existsSync(path) ? JSON.parse(fs.readFileSync(path, "utf8")) : {};
}

exports.self = async ({ github, context, core, exec }) => {
  const env = process.env;
  if (env.HAS_WEBHOOK !== "true") {
    core.setOutput("send", "false");
    return;
  }
  const prefix = `${env.GITHUB_REPOSITORY}/`;
  const ref = env.GITHUB_WORKFLOW_REF;
  const path = (ref.startsWith(prefix) ? ref.slice(prefix.length) : ref).split("@")[0];
  const all = await checks(exec);
  const self = all.find((check) => check.path === path);
  if (!self) {
    throw new Error(`${path} is missing from the checks table in message.jq`);
  }

  const job = await jobs(github, context, env.GITHUB_RUN_ID, env.GITHUB_RUN_ATTEMPT).then(
    (list) => list.find(({ name }) => name === self.job) || {},
    () => ({}),
  );
  const mins = minutes(job);

  // A timed-out job ends cancelled, so its duration tells a timeout from a newer push.
  const timedOut = env.RESULT === "cancelled" && mins !== "" && Number(mins) >= Number(env.TIMEOUT) - 5;
  let send = !(env.RESULT === "cancelled" && !timedOut && context.eventName === "pull_request");
  // Only a push's or nightly's first run waits for the others, so a re-run posts its own message.
  let mode = "single";
  if (context.eventName === "pull_request") {
    const first = env.COMBINE === "true" && env.GITHUB_RUN_ATTEMPT === "1" && context.payload.action !== "labeled";
    mode = first ? "combined" : "rerun";
  } else if (context.eventName === "schedule" && env.GITHUB_RUN_ATTEMPT === "1") {
    if (self.nightly === "posts") {
      mode = "combined";
    } else if (self.nightly === "joins") {
      const poster = all.find(({ nightly }) => nightly === "posts");
      // GitHub can drop a scheduled run, so stay quiet only if the poster has one in this slot.
      send = !(await createdAt(github, context)
        .then((at) => scheduledRun(github, context, poster.path, at))
        .catch(() => undefined));
    }
  }
  fs.writeFileSync(
    `${env.RUNNER_TEMP}/android-self.json`,
    JSON.stringify({
      ...self,
      result: env.RESULT,
      job_id: job.id == null ? "" : String(job.id),
      job_url: job.html_url || "",
      started_at: job.started_at || "",
      stopped_step: stopped(job),
      minutes: mins,
      timed_out: timedOut,
    }),
  );
  core.setOutput("send", String(send));
  core.setOutput("slug", self.slug);
  core.setOutput("mode", mode);
};

exports.wait = async ({ github, context, core, exec }) => {
  const env = process.env;
  // A re-run doesn't wait, so it shows the other checks as they are now.
  const deadline = Date.now() + (env.MODE === "rerun" ? 0 : Number(env.WAIT_MINUTES)) * 60000;
  const scheduled = context.eventName === "schedule";
  const sha = scheduled ? "" : context.payload.pull_request.head.sha;
  let at = "";

  const waitFor = async ({ path, job: name }) => {
    for (;;) {
      let run;
      let found;
      try {
        if (scheduled) {
          at = at || (await createdAt(github, context));
          run = await scheduledRun(github, context, path, at);
        } else {
          const { data } = await github.rest.actions.listWorkflowRunsForRepo({
            ...context.repo,
            head_sha: sha,
            event: "pull_request",
            per_page: 100,
          });
          run = (data.workflow_runs || [])
            .filter((r) => r.path === path)
            .reduce((newest, r) => (newest && newest.run_number > r.run_number ? newest : r), undefined);
        }
        if (!run) {
          return { result: "missing" };
        }
        found = (await jobs(github, context, run.id, run.run_attempt)).find((j) => j.name === name) || {};
      } catch {
        return { result: "unknown" };
      }
      if (found.status === "completed") {
        return {
          result: found.conclusion || "missing",
          job_id: String(found.id),
          job_url: found.html_url || "",
          started_at: found.started_at || "",
          stopped_step: stopped(found),
          minutes: minutes(found),
          run_id: String(run.id),
          attempt: String(run.run_attempt),
          timed_out: false,
        };
      }
      if (!found.status && run.status === "completed") {
        return { result: "missing" };
      }
      if (Date.now() >= deadline) {
        return { result: "running", job_url: found.html_url || "" };
      }
      await new Promise((resolve) => setTimeout(resolve, Number(env.POLL_SECONDS) * 1000));
    }
  };

  const others = {};
  const waitList = (await checks(exec)).filter(
    (check) => check.slug !== env.SELF_SLUG && (!scheduled || check.nightly),
  );
  for (const check of waitList) {
    others[check.slug] = await waitFor(check);
  }
  fs.writeFileSync(`${env.RUNNER_TEMP}/android-others.json`, JSON.stringify(others));
  for (const slug of ["smoke-test", "integration-test"]) {
    const key = slug.replace(/-test$/, "");
    const other = others[slug] || {};
    core.setOutput(`${key}_ran`, String(["success", "failure", "cancelled"].includes(other.result)));
    core.setOutput(`${key}_run_id`, other.run_id || "");
  }
};

exports.logs = async ({ github, context, core, exec }) => {
  const env = process.env;

  const fetchLog = async (slug, name, check) => {
    const out = `job-logs/${slug}-job-log-${env.GITHUB_RUN_ATTEMPT}.log`;
    let id = check.job_id;
    let attempt = Number(check.attempt);
    while (id) {
      const log = await github.rest.actions.downloadJobLogsForWorkflowRun({ ...context.repo, job_id: id }).then(
        ({ data }) => Buffer.from(data || ""),
        () => Buffer.alloc(0),
      );
      if (log.length > 0) {
        fs.writeFileSync(out, log);
        return out;
      }
      attempt -= 1;
      id = "";
      // Only a job that wasn't re-run keeps its start time, so a re-run never gets an old log.
      if (attempt >= 1 && check.started_at) {
        const earlier = await jobs(github, context, check.run_id, attempt).catch(() => []);
        id = (earlier.find((j) => j.name === name && j.started_at === check.started_at) || {}).id || "";
      }
    }
    return "";
  };

  fs.mkdirSync("job-logs", { recursive: true });
  const self = readJSON(`${env.RUNNER_TEMP}/android-self.json`);
  const all = {
    ...readJSON(`${env.RUNNER_TEMP}/android-others.json`),
    [self.slug]: { ...self, run_id: env.GITHUB_RUN_ID, attempt: env.GITHUB_RUN_ATTEMPT },
  };
  for (const check of await checks(exec)) {
    const found = all[check.slug] || {};
    if ((env.MODE === "combined" || check.slug === env.SELF_SLUG) && found.job_id && found.result !== "skipped") {
      core.setOutput(check.slug.replace(/-/g, "_"), await fetchLog(check.slug, check.job, found));
    }
  }
};
