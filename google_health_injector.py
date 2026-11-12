"""Google Health API & Fitbit Cloud Step Injector v6.0.

Features:
- Authorizes against Google Health & Fitness write scopes
- Real-time step injection with natural human cadence (Gaussian pacing)
- Auto-syncs to Google Fitness Cloud and downstream apps (Fitbit, StepUp)
- Zero risk to phone hardware (100% pure cloud API)
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
    from rich.prompt import Prompt, IntPrompt
    from rich.align import Align
    from rich.table import Table
except ImportError:
    print("[!] Run: pip install rich")
    sys.exit(1)

try:
    from google.oauth2.credentials import Credentials
    from google.auth.transport.requests import Request
    from google_auth_oauthlib.flow import InstalledAppFlow
    from googleapiclient.discovery import build
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
    "google_health_token.json",
    "token.json",
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
        "[bold cyan]   ____                 _        _    _            _ _   _     [/bold cyan]\n"
        "[bold cyan]  / ___| ___   ___   __ _| | ___  | |  | | ___  __ _| | |_| |__  [/bold cyan]\n"
        "[bold cyan] | |  _ / _ \\ / _ \\ / _` | |/ _ \\ | |__| |/ _ \\/ _` | | __| '_ \\ [/bold cyan]\n"
        "[bold cyan] | |_| | (_) | (_) | (_| | |  __/ |  __  |  __/ (_| | | |_| | | |[/bold cyan]\n"
        "[bold cyan]  \\____|\\___/ \\___/ \\__, |_|\\___| |_|  |_|\\___|\\__,_|_|\\__|_| |_|[/bold cyan]\n"
        "[bold cyan]                    |___/                                        [/bold cyan]\n"
        "[bold green]        GOOGLE HEALTH API STEP INJECTOR v6.0                    [/bold green]"
    )
    console.print(Panel(Align.center(title_art), border_style="cyan", subtitle="[dim]Official Cloud Health API Sync[/dim]"))


def get_service():
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
                console.print(f"[bold red][!] OAuth client config not found.[/bold red]")
                console.print(f"[yellow]Please place your Google Cloud OAuth credentials file at [bold]{config_file}[/bold] or [bold]client_secret.json[/bold].[/yellow]")
                console.print("[dim]See credentials.example.json or README.md for setup steps.[/dim]")
                sys.exit(1)
            flow = InstalledAppFlow.from_client_secrets_file(config_file, SCOPES)
            creds = flow.run_local_server(port=0)

        try:
            with open(token_file, "w") as token:
                token.write(creds.to_json())
        except Exception:
            pass

    return build("fitness", "v1", credentials=creds)


def get_or_create_stream(service):
    sources = service.users().dataSources().list(userId="me").execute()
    # 1. Prefer Samsung Galaxy S23 FE phone stream (instant sync on phone UI)
    for ds in sources.get("dataSource", []):
        sid = ds.get("dataStreamId", "")
        if "HumanMotionSteps" in sid or "Galaxy S23 FE" in sid:
            return sid

    # 2. Fallback to GoogleHealth stream
    for ds in sources.get("dataSource", []):
        dt = ds.get("dataType", {}).get("name", "")
        sid = ds.get("dataStreamId", "")
        if dt == "com.google.step_count.delta" and "GoogleHealth" in sid:
            return sid

    body = {
        "dataStreamName": "GoogleHealthSteps",
        "type": "raw",
        "application": {"name": "GoogleHealthAPI_Sync"},
        "dataType": {
            "name": "com.google.step_count.delta",
            "field": [{"name": "steps", "format": "integer"}]
        },
        "device": {
            "uid": "GoogleHealthCloud_01",
            "type": "watch",
            "version": "1.0",
            "model": "Fitbit / Google Health Sync",
            "manufacturer": "Google"
        }
    }
    res = service.users().dataSources().create(userId="me", body=body).execute()
    return res["dataStreamId"]


def inject_steps_realtime(service, stream_id: str, target_steps: int = 500, interval_s: int = 4):
    console.print(f"\n[bold green][*] INJECTING {target_steps:,} STEPS VIA GOOGLE HEALTH API[/bold green]")
    console.print(f"[white]Target Stream:[/white] [dim]{stream_id}[/dim]")
    console.print("[dim]Press Ctrl+C at any time to pause.[/dim]\n")

    accumulated = 0
    t_start_total = time.time()

    with Progress(
        SpinnerColumn(),
        TextColumn("[bold cyan]{task.description}"),
        BarColumn(bar_width=None, complete_style="green", finished_style="bold green"),
        TextColumn("[bold white]{task.completed:,}/{task.total:,} steps"),
        TextColumn("[bold yellow]({task.fields[cadence]:.1f} steps/min)"),
        TimeElapsedColumn(),
        console=console,
    ) as progress:
        task = progress.add_task("[green]Streaming steps...", total=target_steps, cadence=0.0)

        while accumulated < target_steps:
            t0 = time.time()
            time.sleep(interval_s)
            t1 = time.time()

            chunk = int((random.gauss(104, 10) / 60.0) * interval_s)
            chunk = max(2, min(chunk, target_steps - accumulated))

            s_ns = int(t0 * 1e9)
            e_ns = int(t1 * 1e9)

            body = {
                "dataSourceId": stream_id,
                "maxEndTimeNs": e_ns,
                "minStartTimeNs": s_ns,
                "point": [{
                    "dataTypeName": "com.google.step_count.delta",
                    "startTimeNanos": s_ns,
                    "endTimeNanos": e_ns,
                    "value": [{"intVal": chunk}]
                }]
            }

            try:
                service.users().dataSources().datasets().patch(
                    userId="me", dataSourceId=stream_id, datasetId=f"{s_ns}-{e_ns}", body=body
                ).execute()
                accumulated += chunk
            except Exception as e:
                console.print(f"[yellow][!] Stream patch notice:[/yellow] {e}")

            elapsed_m = (time.time() - t_start_total) / 60.0
            cadence = (accumulated / elapsed_m) if elapsed_m > 0 else 104.0
            progress.update(task, completed=accumulated, cadence=cadence)

    console.print("\n" + "=" * 65)
    console.print(f"[bold green][\u2713] SUCCESS: Injected {accumulated:,} steps via Google Health API![/bold green]")
    console.print("=" * 65)
    console.print("[white]Pull down Google Fit / StepUp to refresh your updated live count.[/white]\n")


def inject_instant(service, stream_id: str, target_steps: int = 500):
    console.print(f"\n[bold green][*] INSTANT INJECTION: {target_steps:,} steps[/bold green]")
    now = time.time()
    mins = max(3, int(target_steps / 102))
    start_time = now - (mins * 60)

    points = []
    curr = start_time
    remaining = target_steps

    while remaining > 0:
        c = min(remaining, int(random.gauss(102, 10)))
        c = max(10, c)
        p_start_ns = int(curr * 1e9)
        p_end_ns = int((curr + 60) * 1e9)
        points.append({
            "dataTypeName": "com.google.step_count.delta",
            "startTimeNanos": p_start_ns,
            "endTimeNanos": p_end_ns,
            "value": [{"intVal": c}]
        })
        curr += 60
        remaining -= c

    min_ns = points[0]["startTimeNanos"]
    max_ns = points[-1]["endTimeNanos"]

    body = {
        "dataSourceId": stream_id,
        "maxEndTimeNs": max_ns,
        "minStartTimeNs": min_ns,
        "point": points
    }

    service.users().dataSources().datasets().patch(
        userId="me", dataSourceId=stream_id, datasetId=f"{min_ns}-{max_ns}", body=body
    ).execute()

    # Create matching walking session so Google Fit & StepUp update immediately
    try:
        s_ms = int(min_ns) // 1000000
        e_ms = int(max_ns) // 1000000
        sid = f"walk_{s_ms}"
        sess_body = {
            "id": sid,
            "name": "Outdoor Walk",
            "description": "Natural human walking pace",
            "startTimeMillis": s_ms,
            "endTimeMillis": e_ms,
            "activityType": 7,
            "application": {"name": "StepUpHumanAssistant"}
        }
        service.users().sessions().update(userId="me", sessionId=sid, body=sess_body).execute()
    except Exception:
        pass

    console.print(f"[bold green][\u2713] Uploaded {target_steps:,} steps across {mins} natural minutes![/bold green]\n")


def main():
    render_banner()
    service = get_service()
    stream_id = get_or_create_stream(service)
    console.print("[green][\u2713] Authenticated with Google Health API Client![/green]\n")

    while True:
        console.print("[bold yellow]Options:[/bold yellow]")
        console.print("  [1] [bold green]Inject 500 Steps (Test Instant)[/bold green]")
        console.print("  [2] [bold cyan]Real-Time Streamer (Feeds steps every 4s)[/bold cyan]")
        console.print("  [3] [bold white]Custom Step Amount[/bold white]")
        console.print("  [4] [bold red]Exit[/bold red]\n")

        choice = Prompt.ask("Choose option", choices=["1", "2", "3", "4"], default="1")
        if choice == "4":
            break
        elif choice == "1":
            inject_instant(service, stream_id, 500)
        elif choice == "2":
            steps = IntPrompt.ask("[white]Total steps to stream?[/white]", default=500)
            inject_steps_realtime(service, stream_id, steps, interval_s=4)
        elif choice == "3":
            steps = IntPrompt.ask("[white]Enter step count[/white]", default=10000)
            inject_instant(service, stream_id, steps)

        cont = Prompt.ask("Do another injection?", choices=["y", "n"], default="n")
        if cont.lower() != "y":
            break


if __name__ == "__main__":
    main()
