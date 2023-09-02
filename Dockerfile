FROM mozilla/sbt
ADD . /app
WORKDIR /app
CMD sbt
