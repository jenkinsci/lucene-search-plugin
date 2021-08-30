	CACHE=$(free --mega | grep Mem | awk '{print $6}')
    LIMIT=10
    echo "CACHE IS : $CACHE MB"
    echo "LIMIT IS : $LIMIT MB"
    if [ $CACHE -gt $LIMIT ]
    then
      echo "Cleaning cache now..."
      sync && sudo sh -c 'echo 3 > /proc/sys/vm/drop_caches'
      echo "Cleaning finished"
    fi
    sleep 5