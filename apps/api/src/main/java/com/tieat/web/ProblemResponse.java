package com.tieat.web;

import java.net.URI;

record ProblemResponse(
    URI type,
    String title,
    int status,
    String detail,
    URI instance,
    String errorCode
) {
}
