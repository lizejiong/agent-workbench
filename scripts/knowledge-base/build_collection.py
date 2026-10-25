#!/usr/bin/env python3
"""Generate the checked-in Postman v2.1 API management collection; Python stdlib only."""
import json
from pathlib import Path


def build():
    items = []

    def add(name, method, path, body=None, capture="", multipart=False, headers=None, description=""):
        request = {"method": method, "header": headers or [], "url": "{{baseUrl}}" + path,
                   "description": description or "Uses the caller's existing server-mapped identity. Reload versions after 409; inspect job/stages for failures."}
        if body is not None:
            if multipart:
                request["body"] = {"mode": "formdata", "formdata": [{"key": "file", "type": "file", "src": ""},
                    {"key": "metadata", "type": "text", "value": json.dumps(body, ensure_ascii=False)}]}
            else:
                request["header"].append({"key": "Content-Type", "value": "application/json"})
                request["body"] = {"mode": "raw", "raw": json.dumps(body, ensure_ascii=False, indent=2), "options": {"raw": {"language": "json"}}}
        item = {"name": name, "request": request}
        if capture:
            item["event"] = [{"listen": "test", "script": {"type": "text/javascript", "exec": [
                "if (pm.response.code >= 200 && pm.response.code < 300) {",
                "  const result = pm.response.json(); if (result.code === '0') { const d = result.data; " + capture + " }", "}"]}}]
        items.append(item)

    kb_capture = "pm.collectionVariables.set('kbId', d.id); pm.collectionVariables.set('kbVersion', d.version);"
    doc_capture = "pm.collectionVariables.set('documentId', d.id); pm.collectionVariables.set('documentVersion', d.version);"
    changed = "pm.collectionVariables.set('documentVersion', d.documentVersion); if (d.chunks.length) pm.collectionVariables.set('chunkId', d.chunks[0].id);"
    job_capture = "pm.collectionVariables.set('jobId', d.id);"
    add("Management entry", "GET", "/api/knowledge/management")
    add("OpenAPI", "GET", "/openapi/knowledge-base.yaml")
    add("Configured retrieval capabilities", "GET", "/api/knowledge/search-capabilities")
    add("Server model profiles", "GET", "/api/kb-models")
    add("Knowledge bases: search and paginate", "GET", "/api/kbs?current=1&size=20&name=")
    add("Create knowledge base", "POST", "/api/kbs", {"name": "manual-{{$guid}}", "embeddingProfileId": "qwen-embed-1024"}, kb_capture)
    add("Knowledge base details and document count", "GET", "/api/kbs/{{kbId}}", capture=kb_capture)
    add("Rename knowledge base", "PATCH", "/api/kbs/{{kbId}}", {"version": "{{kbVersion}}", "name": "renamed-{{$guid}}"}, kb_capture)
    add("Replace knowledge base metadata", "PUT", "/api/kbs/{{kbId}}", {"version": "{{kbVersion}}", "name": "renamed-{{$guid}}", "description": "Managed through API collection"}, kb_capture)
    add("Bind an old unbound knowledge base", "PUT", "/api/kbs/{{kbId}}/embedding-binding", {"version": "{{kbVersion}}", "embeddingProfileId": "qwen-embed-1024"}, kb_capture)
    add("Upload local file (choose file first)", "POST", "/api/kbs/{{kbId}}/documents", {"ingestionSpec": {"parseProfile": "fast", "splitter": {"maxChars": 1024, "overlapChars": 128, "tableRows": 50}}}, doc_capture,
        multipart=True, headers=[{"key": "Idempotency-Key", "value": "upload-{{$guid}}"}])
    add("Documents: search, state and pagination", "GET", "/api/kbs/{{kbId}}/documents?current=1&size=20&name=&status=")
    add("Document details and latest ingestion time", "GET", "/api/documents/{{documentId}}", capture=doc_capture)
    add("Configure document ingestion", "PATCH", "/api/documents/{{documentId}}", {"version": "{{documentVersion}}", "ingestionSpec": {"schemaVersion": 1, "parser": "AUTO", "parseProfile": "fast", "splitter": {"kind": "STRUCTURAL", "maxChars": 1024, "overlapChars": 128, "tableRows": 50, "toleranceChars": 512}}}, doc_capture)
    add("Authorized original file", "GET", "/api/documents/{{documentId}}/file")
    add("Submit durable ingestion", "POST", "/api/documents/{{documentId}}/ingestion-jobs", {"requestId": "{{jobRequestId}}"}, job_capture,
        description="Keep jobRequestId unchanged to replay the same submission. Save the returned jobId. This call returns quickly; poll the next two requests.")
    add("Confirm replacement of manual changes (review first)", "POST", "/api/documents/{{documentId}}/ingestion-jobs", {"requestId": "replace-{{$guid}}", "confirmOverwriteEdited": True, "expectedDocumentVersion": "{{documentVersion}}"}, job_capture)
    add("Ingestion jobs", "GET", "/api/documents/{{documentId}}/ingestion-jobs?current=1&size=20")
    add("Job state", "GET", "/api/ingestion-jobs/{{jobId}}", capture=job_capture)
    add("Stage timings and sanitized failures", "GET", "/api/ingestion-jobs/{{jobId}}/stages?current=1&size=20")
    add("Retry failed ingestion", "POST", "/api/ingestion-jobs/{{jobId}}/retry", {"requestId": "retry-{{$guid}}"}, job_capture)
    add("Cancel ingestion", "POST", "/api/ingestion-jobs/{{jobId}}/cancel")
    add("Current chunks: paginate", "GET", "/api/documents/{{documentId}}/chunks?current=1&size=20",
        capture="if (d.records.length) pm.collectionVariables.set('chunkId', d.records[0].id);")
    add("Add manual chunk", "POST", "/api/documents/{{documentId}}/chunks", {"version": "{{documentVersion}}", "content": "人工维护正文。"}, changed)
    add("Edit chunk", "PATCH", "/api/documents/{{documentId}}/chunks/{{chunkId}}", {"version": "{{documentVersion}}", "content": "人工修订正文，向量化文本采用同一正文。"}, changed)
    add("Disable chunk independently", "PATCH", "/api/documents/{{documentId}}/chunks/{{chunkId}}/enabled", {"version": "{{documentVersion}}", "enabled": False}, changed)
    add("Enable chunk", "PATCH", "/api/documents/{{documentId}}/chunks/{{chunkId}}/enabled", {"version": "{{documentVersion}}", "enabled": True}, changed)
    add("Batch disable explicit chunk IDs", "POST", "/api/documents/{{documentId}}/chunks/batch-enabled", {"version": "{{documentVersion}}", "enabled": False, "chunkIds": ["{{chunkId}}"]}, changed)
    add("Explicitly enable all current chunks (review first)", "POST", "/api/documents/{{documentId}}/chunks/batch-enabled", {"version": "{{documentVersion}}", "enabled": True, "applyAll": True}, changed)
    add("Disable document", "PATCH", "/api/documents/{{documentId}}/enabled", {"version": "{{documentVersion}}", "enabled": False}, doc_capture)
    add("Reenable document, preserve independent disabled chunks", "PATCH", "/api/documents/{{documentId}}/enabled", {"version": "{{documentVersion}}", "enabled": True}, doc_capture)
    add("Vector search with explicit cosine threshold", "POST", "/api/knowledge/search", {"query": "PostgreSQL connection", "kbIds": ["{{kbId}}"], "topK": 5, "strategy": "VECTOR", "rerank": False, "minScore": 0.5})
    add("Structured retrieval with strategy and independent scores", "POST", "/api/knowledge/retrieve", {"query": "PostgreSQL connection", "kbIds": ["{{kbId}}"], "topK": 5, "strategy": "AUTO"})
    add("Import approved URL", "POST", "/api/kbs/{{kbId}}/documents/import-url", {"url": "{{sourceUrl}}", "filename": "remote.md", "ingest": True},
        "pm.collectionVariables.set('documentId', d.document.id); pm.collectionVariables.set('documentVersion', d.document.version); if (d.jobId) pm.collectionVariables.set('jobId', d.jobId);",
        headers=[{"key": "Idempotency-Key", "value": "import-{{$guid}}"}])
    add("Refresh schedule", "GET", "/api/documents/{{documentId}}/refresh-schedule", capture="pm.collectionVariables.set('scheduleVersion', d.version);")
    add("Configure refresh timezone and cron", "PUT", "/api/documents/{{documentId}}/refresh-schedule", {"version": "{{scheduleVersion}}", "enabled": True, "cron": "0 0 2 * * *", "timezone": "Asia/Shanghai"},
        "pm.collectionVariables.set('scheduleVersion', d.version);")
    add("Manual refresh", "POST", "/api/documents/{{documentId}}/refresh", {"requestId": "refresh-{{$guid}}"})
    add("Refresh run results", "GET", "/api/documents/{{documentId}}/refresh-runs?current=1&size=20")
    add("Keyword synchronization state", "GET", "/api/documents/{{documentId}}/keyword-index")
    add("Keyword synchronization and cleanup including tombstones", "GET", "/api/kbs/{{kbId}}/keyword-sync?current=1&size=20")
    add("Retry keyword synchronization", "POST", "/api/kbs/{{kbId}}/keyword-sync/{{documentId}}/retry")
    add("Committed summary audit", "GET", "/api/kbs/{{kbId}}/audit?current=1&size=20&action=")
    add("Storage compensation operations", "GET", "/api/kbs/{{kbId}}/storage-operations?current=1&size=20")
    add("Retry upload compensation", "POST", "/api/kbs/{{kbId}}/storage-operations/{{storageOperationId}}/retry-cleanup")
    add("Delete current chunk", "DELETE", "/api/documents/{{documentId}}/chunks/{{chunkId}}?version={{documentVersion}}", capture=changed)
    add("Delete document, receive durable cleanup receipt", "DELETE", "/api/documents/{{documentId}}?version={{documentVersion}}", capture="pm.collectionVariables.set('cleanupId', d.id);")
    add("Cleanup receipt", "GET", "/api/document-cleanups/{{cleanupId}}")
    add("Retry failed document cleanup", "POST", "/api/document-cleanups/{{cleanupId}}/retry")
    add("Delete empty knowledge base after all cleanup", "DELETE", "/api/kbs/{{kbId}}?version={{kbVersion}}")
    add("Health", "GET", "/actuator/health")
    add("Metrics (operator account only)", "GET", "/actuator/metrics")
    add("Durable pending job metric (operator only)", "GET", "/actuator/metrics/kb.ingestion.jobs?tag=status:PENDING")

    # Numeric preconditions must be JSON numbers, not quoted strings under strict Jackson validation.
    for item in items:
        body = item["request"].get("body", {})
        if body.get("mode") == "raw":
            for variable in ("kbVersion", "documentVersion", "scheduleVersion"):
                body["raw"] = body["raw"].replace('"{{' + variable + '}}"', '{{' + variable + '}}')
    values = {"baseUrl": "http://127.0.0.1:8080", "username": "", "password": "", "kbId": "", "kbVersion": "1", "documentId": "",
              "documentVersion": "1", "chunkId": "", "jobId": "", "jobRequestId": "manual-job-change-for-new-job", "scheduleVersion": "1",
              "sourceUrl": "", "cleanupId": "", "storageOperationId": ""}
    return {"info": {"name": "Knowledge Base API Management", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
                     "description": "API-only delivery: select requests interactively; do not run destructive operations as a blind collection runner. Set credentials in a local/private environment; never export real keys. Job state is read from backend. Poll active jobs every 2s, stop at terminal state; reduce polling when hidden. Review manual overwrite and applyAll explicitly. There is no generated answer or user administration."},
            "auth": {"type": "basic", "basic": [{"key": "username", "value": "{{username}}", "type": "string"}, {"key": "password", "value": "{{password}}", "type": "string"}]},
            "variable": [{"key": key, "value": value, "type": "string"} for key, value in values.items()], "item": items}


if __name__ == "__main__":
    output = Path(__file__).resolve().parents[2] / "services/backend/src/main/resources/static/openapi/knowledge-base.postman_collection.json"
    output.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Generated", len(build()["item"]), "management requests; no credentials included")
