# -*- coding: utf-8 -*-
"""Tiny HTTP client for the patrol backend (Python standard library only)."""
import json
import urllib.error
import urllib.request


class ApiError(Exception):
    def __init__(self, status, body):
        code = (body or {}).get("code") if isinstance(body, dict) else None
        message = (body or {}).get("message") if isinstance(body, dict) else None
        super(ApiError, self).__init__("HTTP %s %s %s" % (status, code or "", message or ""))
        self.status, self.code = status, code


class PatrolClient(object):
    def __init__(self, base_url, timeout=5.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        # The backend lives on the LAN: never route these calls through a system proxy.
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(self, method, path, body=None, token=None):
        """(status, parsed JSON body); HTTP error statuses are returned, not raised."""
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with self._opener.open(req, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8")
            try:
                return error.code, json.loads(raw) if raw else None
            except ValueError:
                return error.code, None

    def login(self, username, password):
        status, body = self.request("POST", "/api/v1/auth/login", {"username": username, "password": password})
        if status != 200:
            raise ApiError(status, body)
        return body["data"]["token"]

    def robot_status(self, token):
        status, body = self.request("GET", "/api/v1/robot/status", token=token)
        if status != 200:
            raise ApiError(status, body)
        return body["data"]

    def control(self, command, token):
        """(status, body) of POST /api/v1/robot/control."""
        return self.request("POST", "/api/v1/robot/control", {"command": command}, token=token)
