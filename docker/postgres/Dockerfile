FROM postgres:18.0@sha256:41fc5342eefba6cc2ccda736aaf034bbbb7c3df0fdb81516eba1ba33f360162c

# Windows Java/JDBC can send the IANA backward-compatible name Asia/Saigon.
# Restore that alias without changing application dates or installing packages.
RUN test -r /usr/share/zoneinfo/Asia/Ho_Chi_Minh \
    && if [ ! -e /usr/share/zoneinfo/Asia/Saigon ]; then \
         ln -s Ho_Chi_Minh /usr/share/zoneinfo/Asia/Saigon; \
       fi
