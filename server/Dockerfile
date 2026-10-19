FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 JARVIS_BIND=0.0.0.0 PORT=8080 JARVIS_DATA=/tmp/jarvis-voice-data
WORKDIR /app
COPY server/requirements*.txt /app/server/
RUN python -m pip install --no-cache-dir -r server/requirements-phone.txt \
    && useradd --create-home --uid 10001 jarvis
COPY server /app/server
USER jarvis
EXPOSE 8080
CMD ["python", "server/app.py"]
