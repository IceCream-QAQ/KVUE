#!/bin/sh
# CNB runs tests in a root container with a small /dev/shm.
exec /usr/bin/google-chrome --no-sandbox --disable-dev-shm-usage "$@"
