"""StepUp Real-Time Human Walker & Instant Injector v4.1 (Resilient Session Engine)
Fixes:
- Safe optional session creation with auto-fallback (protects against Google 500 BackendError)
- Real-time step dataset streaming directly to verified stream ID
- Full terminal dashboard with live cadence, speed telemetry, and progress bar
"""

import datetime
import os
import random
import sys
import time

try:
    from rich.console import Console
    from rich.panel import Panel
    from rich.progress import Progress, BarColumn, TextColumn, TimeElapsedColumn, SpinnerColumn
    from rich.table import Table
    from rich.prompt import Prompt, IntPrompt
    from rich.align import Align
except ImportError:
    print("[!] Run: pip install rich")
    sys.exit(1)

try:
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    from google_auth_oauthlib.flow import InstalledAppFlow
    from googleapiclient.discovery import build
    from googleapiclient.errors import HttpError
except ImportError:
    print("[!] Run: pip install google-api-python-client google-auth-oauthlib")
    sys.exit(1)

console = Console()

SCOPES = [
    "https://www.googleapis.com/auth/fitness.activity.write",
    "https://www.googleapis.com/auth/fitness.activity.read",
    "https://www.googleapis.com/auth/fitness.body.write",
    "https://www.googleapis.com/auth/fitness.location.write",
]

CONFIG_CANDIDATES = [
    os.environ.get("GOOGLE_CLIENT_CONFIG", ""),
    "client_secret.json",
    "google_health_client.json",
    "credentials.json",
]
TOKEN_CANDIDATES = [
    os.environ.get("GOOGLE_TOKEN_PATH", ""),
    "token.json",
    "google_health_token.json",
]


def resolve_config_file() -> str:
    for path in CONFIG_CANDIDATES:
        if path and os.path.exists(path):
            return path
    return "client_secret.json"


def resolve_token_file() -> str:
    for path in TOKEN_CANDIDATES:
        if path and os.path.exists(path):
            return path
    return "token.json"


def render_banner():
    console.clear()
    title_art = (
        "[bold cyan]  _____ _             _   _         [/bold cyan]\n"
        "[bold cyan] / ____| |           | | | |        [/bold cyan]\n"
        "[bold cyan]| (___ | |_ ___ _ __ | | | |_ __   [/bold cyan]\n"
        "[bold cyan] \\___ \\| __/ _ \\ '_ \\| | | | '_ \\  [/bold cyan]\n"
        "[bold cyan] ____) | ||  __/ |_) | |_| | |_) | [/bold cyan]\n"
        "[bold cyan]|_____/ \\__\\___| .__/ \\___/| .__/  [/bold cyan]\n"
        "[bold cyan]               | |         | |     [/bold cyan]\n"
        "[bold cyan]               |_|         |_|     [/bold cyan]\n"
        "[bold green]   STEPUP REAL-TIME ENGINE v4.1   [/bold green]"
    )
    console.print(Panel(Align.center(title_art), border_style="cyan", subtitle="[dim]Resilient Real-Time Human Cadence Simulator[/dim]"))


def get_fitness_service():
    config_file = resolve_config_file()
    token_file = resolve_token_file()

    creds = None
    if os.path.exists(token_file):
        creds = Credentials.from_authorized_user_file(token_file, SCOPES)

    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            if not os.path.exists(config_file):
                console.print(f"[bold red][!] '{config_file}' not found.[/bold red]")
                console.print("[yellow]Please place your Google Cloud OAuth credentials at client_secret.json.[/yellow]")
                console.print("[dim]See credentials.example.json or README.md for setup instructions.[/dim]")
                sys.exit(1)
            flow = InstalledAppFlow.from_client_secrets_file(config_file, SCOPES)
            creds = flow.run_local_server(port=0)

        try:
            with open(token_file, "w") as token:
                token.write(creds.to_json())
        except Exception:
            pass

    return build("fitness", "v1", credentials=creds)


def get_or_create_stream(service) -> str:
    """Finds existing step stream for the authenticated user, or registers a native phone stream."""
    sources = service.users().dataSources().list(userId="me").execute()

    # 1. Prefer phone stream (instant sync on mobile phone UI)
    for ds in sources.get("dataSource", []):
        dt = ds.get("dataType", {}).get("name", "")
        sid = ds.get("dataStreamId", "")
        if dt == "com.google.step_count.delta" and ("HumanMotion" in sid or "Galaxy" in sid or "steps" in sid):
            if "derived:" not in sid:
                return sid

    # 2. Prefer any writable step delta stream
    for ds in sources.get("dataSource", []):
        dt = ds.get("dataType", {}).get("name", "")
        sid = ds.get("dataStreamId", "")
        if dt == "com.google.step_count.delta" and "derived:" not in sid:
            return sid

    # 3. Create a clean raw stream matching mobile phone pedometer
    device_model = os.environ.get("DEVICE_MODEL", "Galaxy S23 FE")
    device_mfr = os.environ.get("DEVICE_MANUFACTURER", "samsung")
    device_uid = os.environ.get("DEVICE_UID", "d457f782")
    app_name = os.environ.get("APP_NAME", "StepUpHumanAssistant")

    body = {
        "dataStreamName": "HumanMotionSteps",
        "type": "raw",
        "application": {"name": app_name},
        "dataType": {
            "name": "com.google.step_count.delta",
            "field": [{"name": "steps", "format": "integer"}]
        },
        "device": {
            "uid": device_uid,
            "type": "phone",
            "version": "14",
            "model": device_model,
            "manufacturer": device_mfr
        }
    }
    res = service.users().dataSources().create(userId="me", body=body).execute()
    return res["dataStreamId"]


def send_step_batch(service, stream_id: str, step_count: int, start_time: float, end_time: float):
    start_ns = int(start_time * 1e9)
    end_ns = int(end_time * 1e9)
    dataset_id = f"{start_ns}-{end_ns}"

    body = {
        "dataSourceId": stream_id,
        "maxEndTimeNs": end_ns,
        "minStartTimeNs": start_ns,
        "point": [
            {
                "dataTypeName": "com.google.step_count.delta",
                "startTimeNanos": start_ns,
                "endTimeNanos": end_ns,
                "value": [{"intVal": step_count}],
            }
        ],
    }

    try:
        service.users().dataSources().datasets().patch(
            userId="me", dataSourceId=stream_id, datasetId=dataset_id, body=body
        ).execute()
        return True
    except HttpError as e:
        console.print(f"[yellow][!] Stream patch notice: {e}[/yellow]")
        return False


def run_realtime_walker(service, stream_id: str, target_steps: int, tick_interval: int = 5):
    console.print(f"\n[bold green][*] REAL-TIME WALKER ACTIVE[/bold green]")
    console.print(f"[white]Target Goal:[/white] [bold cyan]{target_steps:,} steps[/bold cyan] | [white]Interval:[/white] Every {tick_interval}s")
    console.print(f"[white]Active Stream:[/white] [dim]{stream_id}[/dim]")
    console.print("[dim]Injecting steps in real time. Press Ctrl+C anytime to pause or stop.[/dim]\n")

    accumulated = 0
    session_start_time = time.time()
    session_id = f"walk_{int(session_start_time)}"

    # Safely create session (non-blocking if Google Fitness throws 500)
    try:
        service.users().sessions().update(
            userId="me",
            sessionId=session_id,
            body={
                "id": session_id,
                "name": "Live Walking Workout",
                "description": "Continuous real-time walking cadence",
                "startTimeMillis": int(session_start_time * 1000),
                "endTimeMillis": int((session_start_time + 60) * 1000),
                "activityType": 7,  # Walking
                "application": {"name": "StepUpHumanAssistant"},
            },
        ).execute()
    except Exception:
        # Step points inject directly into stream without requiring session metadata
        pass

    try:
        with Progress(
            SpinnerColumn(),
            TextColumn("[bold cyan]{task.description}"),
            BarColumn(bar_width=None, complete_style="green", finished_style="bold green"),
            TextColumn("[bold white]{task.completed:,}/{task.total:,} steps"),
            TextColumn("[bold yellow]({task.fields[rate]:.1f} steps/min)"),
            TimeElapsedColumn(),
            console=console,
        ) as progress:
            task = progress.add_task("[green]Walking live...", total=target_steps, rate=0.0)

            while accumulated < target_steps:
                t_start = time.time()
                time.sleep(tick_interval)
                t_end = time.time()

                # Calculate realistic human cadence
                expected_delta = int((random.gauss(104, 10) / 60.0) * tick_interval)
                if random.random() < 0.07:
                    expected_delta = max(1, int(expected_delta * 0.3))

                delta = max(2, min(expected_delta, target_steps - accumulated))

                if send_step_batch(service, stream_id, delta, t_start, t_end):
                    accumulated += delta

                elapsed_mins = (time.time() - session_start_time) / 60.0
                curr_cadence = accumulated / elapsed_mins if elapsed_mins > 0 else 102.0
                progress.update(task, completed=accumulated, rate=curr_cadence)

    except KeyboardInterrupt:
        console.print("\n[yellow][!] Live walking paused by user.[/yellow]")

    # Safely close session
    try:
        session_end_time = time.time()
        service.users().sessions().update(
            userId="me",
            sessionId=session_id,
            body={
                "id": session_id,
                "name": "Live Walking Workout",
                "description": "Continuous real-time walking cadence",
                "startTimeMillis": int(session_start_time * 1000),
                "endTimeMillis": int(session_end_time * 1000),
                "activityType": 7,
                "application": {"name": "StepUpHumanAssistant"},
            },
        ).execute()
    except Exception:
        pass

    console.print(Panel(
        f"[bold green]\u2713 Real-Time Session Finished: {accumulated:,} steps streamed live![/bold green]\n"
        "[white]Pull down Google Fit / StepUp to sync newly injected steps.[/white]",
        border_style="green"
    ))


def run_instant_backfill(service, stream_id: str, target_steps: int):
    now = time.time()
    total_minutes = max(10, int(target_steps / 102))
    walk_start = now - (total_minutes * 60)
    walk_end = now - 15

    points = []
    curr = walk_start
    remaining = target_steps

    while remaining > 0:
        chunk = int(random.gauss(102, 12))
        chunk = max(15, min(chunk, remaining))
        points.append({
            "dataTypeName": "com.google.step_count.delta",
            "startTimeNanos": int(curr * 1e9),
            "endTimeNanos": int((curr + 60) * 1e9),
            "value": [{"intVal": chunk}],
        })
        curr += 60
        remaining -= chunk

    start_ns = int(walk_start * 1e9)
    end_ns = int(walk_end * 1e9)
    dataset_id = f"{start_ns}-{end_ns}"

    body = {
        "dataSourceId": stream_id,
        "maxEndTimeNs": end_ns,
        "minStartTimeNs": start_ns,
        "point": points,
    }

    service.users().dataSources().datasets().patch(
        userId="me", dataSourceId=stream_id, datasetId=dataset_id, body=body
    ).execute()

    try:
        session_id = f"walk_{int(walk_start)}"
        service.users().sessions().update(
            userId="me",
            sessionId=session_id,
            body={
                "id": session_id,
                "name": "Outdoor Walk",
                "description": "Natural human cadence",
                "startTimeMillis": int(walk_start * 1000),
                "endTimeMillis": int(walk_end * 1000),
                "activityType": 7,
                "application": {"name": "StepUpHumanAssistant"},
            },
        ).execute()
    except Exception:
        pass

    console.print(Panel(
        f"[bold green]\u2713 SUCCESS: Injected {target_steps:,} steps instantly![/bold green]\n"
        f"[dim]Simulated {total_minutes} mins ({total_minutes/60:.1f} hrs) human walk.[/dim]\n\n"
        "[white]Pull down Google Fit / StepUp to sync.[/white]",
        border_style="green"
    ))


def main():
    render_banner()
    service = get_fitness_service()
    stream_id = get_or_create_stream(service)
    console.print(f"[green][\u2713] Connected to Google Fitness Live Engine[/green]")
    console.print(f"[dim]Active Stream: {stream_id}[/dim]\n")

    while True:
        console.print("[bold yellow]Select Pacing Mode:[/bold yellow]")
        console.print("  [1] [bold green]Real-Time Live Walker[/bold green] (Feeds ~10 steps every 5s live with speed telemetry)")
        console.print("  [2] [bold cyan]Instant Turbo Backfill[/bold cyan] (One-shot: injects thousands in 2s spread across past hours)")
        console.print("  [3] [bold red]Exit[/bold red]\n")

        choice = Prompt.ask("Choose mode", choices=["1", "2", "3"], default="1")
        if choice == "3":
            break

        steps = IntPrompt.ask("[bold white]Total step goal[/bold white]", default=5000)

        if choice == "1":
            interval = IntPrompt.ask("[white]Update interval in seconds (default 5s)[/white]", default=5)
            run_realtime_walker(service, stream_id, steps, tick_interval=interval)
        elif choice == "2":
            run_instant_backfill(service, stream_id, steps)

        again = Prompt.ask("\nRun another session?", choices=["y", "n"], default="n")
        if again.lower() != "y":
            break


if __name__ == "__main__":
    main()
