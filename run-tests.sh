#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Create output directory for reports
OUTPUT_DIR="test-reports"
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

# First arg is total runs
if [ "$1" != "" ]; then
    total_runs=$1
else
    total_runs=10
fi

success_count=0
failure_count=0

echo "Running test $total_runs times..."
echo "------------------------"
start_time=$(date +%s)

for ((i=1; i<=total_runs; i++)); do
    echo -n "Run #$i: "
    
    # Run the specific test with correct flags
    touch "$OUTPUT_DIR/test-run-$i.txt"
    ./gradlew testDebugUnitTest --rerun-tasks --daemon > "$OUTPUT_DIR/test-run-$i.txt" 2>&1
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}PASSED${NC}"
        ((success_count++))
    else
        echo -e "${RED}FAILED${NC}"
        # Print the output of the test run
        echo "Output of test run $i:"
        cat "$OUTPUT_DIR/test-run-$i.txt"
        # Copy the HTML report with run number
        cp -R "./android/build/reports/tests/" "$OUTPUT_DIR/failure-run-$i/"
        ((failure_count++))
    fi
done

echo "------------------------"
echo "Execution time: $(($(date +%s) - start_time)) seconds"
echo "Results:"
echo "Total runs: $total_runs"
echo -e "Successes: ${GREEN}$success_count${NC}"
echo -e "Failures: ${RED}$failure_count${NC}"
echo "Failure rate: $(( (failure_count * 100) / total_runs ))%"

if [ $failure_count -gt 0 ]; then
    echo "Failure reports saved in $OUTPUT_DIR/"
fi 
