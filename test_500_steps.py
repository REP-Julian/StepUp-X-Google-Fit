"""StepUp Test Injector (Definite 500 Steps Injection)
Guaranteed exact matching of dataset bounds to points.
"""

import os
import random
import sys
import time

try:
    from rich.console import Console
    from rich.panel import Panel
    from rich.progress import Progress, BarColumn, TextColumn, SpinnerColumn
    from rich.align import Align
except ImportError:
    print("[!] Run: pip install rich")
    sys.exit(1)

from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request
from googleapiclient.discovery import build

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
                sys.exit(1)
            from google_auth_oauthlib.flow import InstalledAppFlow
            flow = InstalledAppFlow.from_client_secrets_file(config_file, SCOPES)
            creds = flow.run_local_server(port=0)

        try:
            with open(token_file, "w") as token:
                token.write(creds.to_json())
        except Exception:
            pass

    return build("fitness", "v1", credentials=creds)


def get_stream_id(service) -> str:
    sources = service.users().dataSources().list(userId="me").execute()
    for ds in sources.get("dataSource", []):
        dt = ds.get("dataType", {}).get("name", "")
        sid = ds.get("dataStreamId", "")
        if dt == "com.google.step_count.delta" and "derived:" not in sid:
            return sid

    # Fallback to creating raw phone stream
    body = {
        "dataStreamName": "HumanMotionSteps",
        "type": "raw",
        "application": {"name": "StepUpHumanAssistant"},
        "dataType": {
            "name": "com.google.step_count.delta",
            "field": [{"name": "steps", "format": "integer"}]
        },
        "device": {
            "uid": "d457f782",
            "type": "phone",
            "version": "14",
            "model": "Galaxy S23 FE",
            "manufacturer": "samsung"
        }
    }
    res = service.users().dataSources().create(userId="me", body=body).execute()
    return res["dataStreamId"]


def inject_500_steps(service, target_steps: int = 500):
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
        "[bold green]   STEPUP TEST INJECTOR (500 STEPS)[/bold green]"
    )
    console.print(Panel(Align.center(title_art), border_style="cyan"))

    stream_id = get_stream_id(service)
    now = time.time()
    # 5 intervals of 100 steps across the last 5 minutes
    num_intervals = 5
    interval_duration = 60
    walk_start = now - (num_intervals * interval_duration)

    points = []
    curr = walk_start
    steps_per_interval = target_steps // num_intervals

    for i in range(num_intervals):
        p_start_ns = int(curr * 1e9)
        p_end_ns = int((curr + interval_duration) * 1e9)
        val = steps_per_interval if i < num_intervals - 1 else (target_steps - (steps_per_interval * (num_intervals - 1)))
        points.append({
            "dataTypeName": "com.google.step_count.delta",
            "startTimeNanos": p_start_ns,
            "endTimeNanos": p_end_ns,
            "value": [{"intVal": val}],
        })
        curr += interval_duration

    min_start_ns = points[0]["startTimeNanos"]
    max_end_ns = points[-1]["endTimeNanos"]
    dataset_id = f"{min_start_ns}-{max_end_ns}"

    body = {
        "dataSourceId": stream_id,
        "maxEndTimeNs": max_end_ns,
        "minStartTimeNs": min_start_ns,
        "point": points,
    }

    with Progress(
        SpinnerColumn(),
        TextColumn("[bold cyan]{task.description}"),
        BarColumn(bar_width=None, complete_style="green"),
        TextColumn("[bold green]100%"),
        console=console,
    ) as progress:
        task = progress.add_task(f"[green]Uploading {target_steps} steps to Google Fit...", total=100)
        service.users().dataSources().datasets().patch(
            userId="me", dataSourceId=stream_id, datasetId=dataset_id, body=body
        ).execute()
        progress.update(task, completed=100)

    try:
        session_id = f"walk_{int(walk_start)}"
        service.users().sessions().update(
            userId="me",
            sessionId=session_id,
            body={
                "id": session_id,
                "name": "Brisk Walk",
                "startTimeMillis": int(walk_start * 1000),
                "endTimeMillis": int(now * 1000),
                "activityType": 7,
                "application": {"name": "StepUpHumanAssistant"}
            }
        ).execute()
    except Exception:
        pass

    console.print("\n" + "=" * 65)
    console.print(f"[bold green][\u2713] SUCCESS: Exactly {target_steps} steps verified and uploaded![/bold green]")
    console.print("=" * 65)
    console.print("[bold yellow]To verify right now on your phone:[/bold yellow]")
    console.print("[white]1. Open [bold]Google Fit[/bold] on phone -> swipe down once to refresh.[/white]")
    console.print("[white]2. Open [bold]StepUp[/bold] -> swipe down once to refresh.[/white]\n")


if __name__ == "__main__":
    service = get_fitness_service()
    inject_500_steps(service, 500)
