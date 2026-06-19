for scenario in 'FM2RR'; do
  for name in ~/ANTS/smoothie/networks/topology-zoo/*; do
    topo=$(basename "$name")
    if [[ "$topo" != "GtsCe" && "$topo" != "statistics.csv" ]]; then
      $(/usr/libexec/java_home -v 17)/bin/java -jar -Xmx20G bazel-bin/projects/smoothie/main_deploy.jar ${topo} ${scenario}
    fi
  done
done