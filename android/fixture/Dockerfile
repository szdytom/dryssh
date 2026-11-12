FROM ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server tmux neovim git locales && apt-get clean
RUN useradd -m -s /bin/bash dryssh && echo 'dryssh:dryssh-test' | chpasswd && mkdir /run/sshd && locale-gen en_US.UTF-8
RUN printf '\nPasswordAuthentication yes\nUsePAM no\nPrintMotd no\n' >> /etc/ssh/sshd_config
COPY --chown=dryssh:dryssh authorized_keys /home/dryssh/.ssh/authorized_keys
COPY --chown=dryssh:dryssh acceptance.lua tmux-acceptance.conf /home/dryssh/
ENV LANG=en_US.UTF-8
EXPOSE 22
CMD ["/bin/sh", "-c", "ssh-keygen -A && exec /usr/sbin/sshd -D -e"]
