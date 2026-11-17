"""SQLite 当前图只允许一个 worker 独占目录；不假定内存对象可跨实例使用。"""

import os


class StateDirectoryLock:
    def __init__(self, path):
        self.path = path

    def __enter__(self):
        self.file = (self.path / "runtime.lock").open("a+b")
        if self.file.seek(0, 2) == 0:
            self.file.write(b"0")
            self.file.flush()
        self.file.seek(0)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise RuntimeError(
                "AGENT_STATE_DIR 已由其他 worker 使用；本部署须使用单 AI worker"
            ) from None
        return self

    def __exit__(self, *_):
        self.file.close()
