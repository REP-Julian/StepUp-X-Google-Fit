import os
import json
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build

SCOPES = [
    "https://www.googleapis.com/auth/fitness.activity.write",
    "https://www.googleapis.com/auth/fitness.activity.read",
    "https://www.googleapis.com/auth/fitness.body.write",
    "https://www.googleapis.com/auth/fitness.location.write",
]

config_candidates = [
    os.environ.get("GOOGLE_CLIENT_CONFIG", ""),
    "client_secret.json",
    "google_health_client.json",
    "credentials.json",
]
token_candidates = [
    os.environ.get("GOOGLE_TOKEN_PATH", ""),
    "token.json",
    "google_health_token.json",
]

config_file = next((p for p in config_candidates if p and os.path.exists(p)), "client_secret.json")
token_file = next((p for p in token_candidates if p and os.path.exists(p)), "token.json")

creds = None
if os.path.exists(token_file):
    creds = Credentials.from_authorized_user_file(token_file, SCOPES)

if not creds or not creds.valid:
    if creds and creds.expired and creds.refresh_token:
        creds.refresh(Request())
    else:
        if not os.path.exists(config_file):
            print(f"[!] Config file not found at {config_file}")
            exit(1)
        flow = InstalledAppFlow.from_client_secrets_file(config_file, SCOPES)
        creds = flow.run_local_server(port=0)

    try:
        with open(token_file, "w") as f:
            f.write(creds.to_json())
    except Exception:
        pass

service = build("fitness", "v1", credentials=creds)

res = service.users().dataSources().list(userId="me").execute()
sources = res.get("dataSource", [])
print(f"Total sources: {len(sources)}")
for s in sources:
    dt = s.get("dataType", {}).get("name", "")
    sid = s.get("dataStreamId", "")
    if "step_count" in dt:
        print(f"ID: {sid} | TYPE: {s.get('type')}")
