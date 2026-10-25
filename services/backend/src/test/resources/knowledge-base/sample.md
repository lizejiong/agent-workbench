# Deployment Guide

## Database

The application reads KB_DB_URL from the environment. Use a private PostgreSQL database and keep the account password in the deployment secret configuration.

The metadata transaction is separate from object storage. A document keeps its original object key for authorized downloads.

```java
String url = System.getenv("KB_DB_URL");
connect(url);
```

| Field | Value |
| --- | --- |
| host | localhost |
| port | 54329 |
| mode | private |

## Recovery

- Inspect the persisted stage and error code.
- Correct the configuration, then invoke ingestion again.

An unsuccessful generation must not replace the previous published evidence.
