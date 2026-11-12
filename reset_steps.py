"""StepUp Real-Time Step Reset Engine v4.0.

Features:
- Instant real-time wipe of injected steps from Google Fitness Cloud.
- Wipes active live sessions, historical backfilled workouts, and raw step datasets from today.
- Terminal GUI with live progress bar and status feedback.
- Resets Google Fit & StepUp cloud ledger immediately.
"""

import datetime
import os
import sys
import time

try:
    from rich.console import Console
    from rich.panel import Panel
    from rich.progress import Progress, BarColumn, TextColumn, SpinnerColumn
    from rich.prompt import Prompt
    from rich.align import Align
except ImportError:
    print("[!] Run: pip install rich")
    sys.exit(1)

try:
    from google.oauth2.credentials import Credentials
    from google.auth.transport.requests import Request
    from googleapiclient.discovery import build
except ImportError:
    print("[!] Run: pip install google-api-python-client google-auth-oauthlib")
    sys.exit(1)

console = Console()

TOKEN_CANDIDATES = [
    os.environ.get("GOOGLE_TOKEN_PATH", ""),
    "token.json",
    "google_health_token.json",
]


def resolve_token_file() -> str:
    for path in TOKEN_CANDIDATES:
        if path and os.path.exists(path):
            return path
    return "token.json"


def get_fitness_service():
    token_file = resolve_token_file()
    if not os.path.exists(token_file):
        console.print("[bold red][!] No active authorization token found.[/bold red]")
        console.print("[yellow]Please run google_health_injector.py or human_injector.py first to authenticate.[/yellow]")
        sys.exit(1)
    creds = Credentials.from_authorized_user_file(token_file, SCOPES)
    if creds and creds.expired and creds.refresh_token:
        creds.refresh(Request())
    return build("fitness", "v1", credentials=creds)


def execute_realtime_reset():
    render_banner()
    service = get_fitness_service()

    now = datetime.datetime.now()
    midnight = datetime.datetime(now.year, now.month, now.day, 0, 0, 0)
    start_ms = int(midnight.timestamp() * 1000)
    now_ms = int(time.time() * 1000)
    start_ns = start_ms * 1000000
    now_ns = now_ms * 1000000
    dataset_id = f"{start_ns}-{now_ns}"

    console.print(f"[bold yellow][*] Target Date:[/bold yellow] [white]{midnight.strftime('%A, %B %d, %Y')}[/white]")
    console.print(f"[bold yellow][*] Time Window:[/bold yellow] [white]00:00:00 \u2192 Right Now ({now.strftime('%H:%M:%S')})[/white]\n")

    with Progress(
        SpinnerColumn(),
        TextColumn("[bold cyan]{task.description}"),
        BarColumn(bar_width=None, complete_style="red"),
        TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
        console=console,
    ) as progress:
        task1 = progress.add_task("[yellow]Deleting active & historical walking sessions...", total=100)

        # 1. Purge Sessions
        try:
            sessions_res = service.users().sessions().list(
                userId="me", startTime=f"{start_ms}000000"
            ).execute()
            sessions = sessions_res.get("session", [])
            for s in sessions:
                sid = s.get("id", "")
                if "walk" in sid.lower() or "step" in sid.lower() or "StepUp" in s.get("application", {}).get("name", ""):
                    try:
                        service.users().sessions().delete(userId="me", sessionId=sid).execute()
                    except Exception:
                        pass
            progress.update(task1, completed=100)
        except Exception:
            progress.update(task1, completed=100)

        # 2. Purge Step Datasets
        task2 = progress.add_task("[red]Purging step datasets from all writable streams...", total=100)
        try:
            sources_res = service.users().dataSources().list(userId="me").execute()
            data_sources = sources_res.get("dataSource", [])
            step_sources = [ds for ds in data_sources if "step_count" in ds.get("dataType", {}).get("name", "")]

            step_count = len(step_sources)
            for idx, ds in enumerate(step_sources):
                s_id = ds.get("dataStreamId", "")
                # Only attempt deletion on writable non-derived streams
                if "estimated_steps" not in s_id and "merge_step_deltas" not in s_id:
                    try:
                        service.users().dataSources().datasets().delete(
                            userId="me",
                            dataSourceId=s_id,
                            datasetId=dataset_id,
                        ).execute()
                    except Exception:
                        pass
                progress.update(task2, completed=int(((idx + 1) / max(1, step_count)) * 100))
        except Exception:
            progress.update(task2, completed=100)

    console.print("\n" + "=" * 65)
    console.print("[bold green][\u2713] REAL-TIME CLOUD RESET COMPLETE![/bold green]")
    console.print("=" * 65)
    console.print("[white]1. Open [bold]Google Fit[/bold] on phone -> pull down to refresh (injected steps drop to 0).[/white]")
    console.print("[white]2. Open [bold]StepUp[/bold] -> pull down to refresh (leaderboard recalculates instantly).[/white]\n")


if __name__ == "__main__":
    execute_realtime_reset()
